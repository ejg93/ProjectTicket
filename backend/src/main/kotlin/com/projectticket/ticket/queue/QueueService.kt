package com.projectticket.ticket.queue

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service

/**
 * 줄 세우기(`D12`). 오픈 순간에 몰리는 사람을 ZSET 에 세워 **선점 요청이 DB 에 닿는 속도의 상한**을 만든다.
 *
 * 토큰 발급·입장은 여기 없다 — 22 의 스케줄러가 한다. 이 청크는 줄에 세우고 순번을 답하는 것까지다.
 *
 * **좌석 상태는 Redis 에 없다.** 줄이 통째로 날아가도 팔린 좌석은 DB 에 그대로고, 사람들이 다시 줄을 설 뿐이다(`D12` 「장애」).
 */
@Service
class QueueService(
    private val redis: StringRedisTemplate,
    private val jdbc: JdbcClient,
    private val admission: AdmissionService,
) {

    /**
     * 진입·재진입. **이미 줄에 있으면 진입 시각을 안 바꾼다**(`ZADD NX`) — 새로고침해도 순번이 그대로인 이유가 그 한 글자다.
     * member 가 계정이라 한 사람이 줄에 두 번 못 선다.
     */
    fun enter(performanceId: Long, accountId: Long): Position {
        requireOpen(performanceId)
        // 이미 들어온 사람을 다시 줄에 세우면 방금 얻은 자리를 스스로 버린다 — 새로고침이 그 모양이다(`D12`).
        admission.tokenFor(performanceId, accountId)?.let { return Position.admitted(it) }

        val rank = redis.execute(ENTER, listOf(QueueKeys.waiting(performanceId)), accountId.toString())
        // 방금 넣은 member 의 순번이라 없을 수가 없다. 없으면 스크립트가 바뀐 것이다.
        return position(checkNotNull(rank) { "진입 직후 순번이 없다: performance_id=$performanceId" })
    }

    /** 폴링(2초)이 부르는 자리(`D12`). 하트비트 기록은 24 가 여기에 더한다 */
    fun position(performanceId: Long, accountId: Long): Position {
        requireOpen(performanceId)
        // 입장한 사람은 줄에서 빠져 있다(`ZPOPMIN`). 토큰을 먼저 보지 않으면 방금 들어온 사람이 404 를 받는다.
        admission.tokenFor(performanceId, accountId)?.let { return Position.admitted(it) }

        val rank = redis.opsForZSet().rank(QueueKeys.waiting(performanceId), accountId.toString())
            ?: throw TicketException(
                ErrorCode.NOT_IN_QUEUE,
                "줄에 없다: performance_id=$performanceId account_id=$accountId",
            )
        return position(rank)
    }

    /**
     * 회차가 끝나면 그 줄을 통째로 지운다(16a·17a).
     *
     * **부르는 쪽이 커밋 뒤에 부른다.** 트랜잭션 안에서 지우면 롤백된 종료가 남의 줄을 날린다 —
     * Redis 에는 되돌릴 방법이 없다.
     */
    fun drop(performanceId: Long) {
        redis.delete(QueueKeys.ofPerformance(performanceId))
    }

    /** 순번은 1부터, 예상 대기는 입장 속도로 나눈 올림 초다. R 이 설정값이라 근사다(`D12`) */
    private fun position(rank: Long): Position =
        Position.waiting(rank = rank + 1, etaSeconds = (rank + ADMIT_PER_SECOND) / ADMIT_PER_SECOND)

    /**
     * 판매 중인 회차에만 줄이 선다. 닫힌 회차는 410 이다 — 「있었는데 끝났다」가 410 의 뜻이고(`D5`),
     * 화면은 그 코드로 줄을 걷는다.
     */
    private fun requireOpen(performanceId: Long) {
        val status = jdbc.sql("select status from performance where performance_id = :id")
            .param("id", performanceId)
            .query(String::class.java)
            .optional()
            .orElse(null)
            ?: throw TicketException(ErrorCode.PERFORMANCE_NOT_FOUND, "그런 회차가 없다: performance_id=$performanceId")

        if (status != OPEN) {
            throw TicketException(ErrorCode.QUEUE_CLOSED, "대기열이 없는 회차다: performance_id=$performanceId status=$status")
        }
    }

    /**
     * 줄의 대답. **무엇이 들었는지는 [state] 가 정한다**(사용자 선택) — 대기 중에는 순번이, 입장 뒤에는 토큰이 온다.
     * 입장한 사람에게 `rank = 0` 을 주지 않는다: 0 이 「줄 맨 앞」인지 「이미 들어감」인지를 화면이 다시 판단해야 한다(`D14`).
     */
    data class Position(
        val state: String,
        val rank: Long? = null,
        val etaSeconds: Long? = null,
        val admissionToken: String? = null,
    ) {
        companion object {
            const val WAITING = "waiting"
            const val ADMITTED = "admitted"

            /** `rank` 는 1부터다 */
            fun waiting(rank: Long, etaSeconds: Long): Position =
                Position(WAITING, rank = rank, etaSeconds = etaSeconds)

            /** 선점 요청에 이 토큰을 `X-Admission-Token` 으로 싣는다(23) */
            fun admitted(token: String): Position = Position(ADMITTED, admissionToken = token)
        }
    }

    companion object {
        private const val OPEN = "open"

        /** 입장 속도 R. 값은 [AdmissionService] 가 정한다 — 들이는 쪽과 예상 대기가 갈리면 화면이 거짓말을 한다 */
        const val ADMIT_PER_SECOND = AdmissionService.ADMIT_PER_SECOND

        /**
         * 진입 한 번을 원자로 만든다 — 시각을 Redis 가 주고(`TIME`), `NX` 로 첫 자리를 지키고, 순번까지 한 번에 답한다.
         *
         * 시각을 앱이 만들면 인스턴스 시계가 어긋난 만큼 순번이 뒤집힌다(`D7` 「앱 시계를 안 쓴다」의 Redis 판).
         * `TIME` 은 비결정 명령이라 예전 Redis 에서는 쓰기 앞에 못 썼다 — 7 은 효과 복제라 된다.
         */
        private val ENTER = DefaultRedisScript<Long>(
            """
            local t = redis.call('TIME')
            redis.call('ZADD', KEYS[1], 'NX', t[1] * 1000 + math.floor(t[2] / 1000), ARGV[1])
            return redis.call('ZRANK', KEYS[1], ARGV[1])
            """.trimIndent(),
            Long::class.java,
        )
    }
}
