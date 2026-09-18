package com.projectticket.ticket.queue

import com.projectticket.ticket.observability.TicketMetrics
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * 문을 여는 쪽(`D12` 「입장」). 줄 앞에서 정원이 허락하는 만큼 빼내 토큰을 발급한다.
 *
 * **Lua 하나로 한다.** 정원 계산과 `ZPOPMIN` 이 갈리면 두 인스턴스가 같은 사람을 두 번 들이거나
 * 정원을 넘겨 들인다 — 스크립트 안이면 그 사이에 아무도 못 끼어든다. 두 대가 돌면 5초에 200명이
 * 들어가는 것이지 한 명이 두 번 들어가는 것이 아니다(속도를 설정값과 맞추는 것은 33 의 Redis 락이다).
 *
 * **토큰은 서명이 아니라 저장이다**(`D12` 「토큰」). 서명 토큰은 만료 전에 못 죽이는데, 선점에 성공하면
 * 그 자리에서 반납받아야 정원이 돈다.
 */
@Service
class AdmissionService(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val metrics: TicketMetrics,
) {

    private val random = SecureRandom()

    /**
     * 한 회차의 줄에서 들일 수 있는 만큼 들인다.
     *
     * @return 이번에 들인 사람 수. 줄이 비었거나 정원이 찼으면 0
     */
    fun admit(performanceId: Long): Long {
        val waitingKey = QueueKeys.waiting(performanceId)
        val waiting = redis.opsForZSet().size(waitingKey) ?: 0
        if (waiting == 0L) return 0

        // 줄 길이보다 많은 토큰을 만들 이유가 없다. 토큰 생성은 앱이 한다 — Lua 에는 난수원이 없다.
        val tokens = List(minOf(waiting, BATCH_SIZE).toInt()) { newToken() }
        val args = listOf(
            CAPACITY.toString(),
            TOKEN_TTL.toMillis().toString(),
            QueueKeys.ADMISSION_PREFIX,
            QueueKeys.admissionByAccountPrefix(performanceId),
            performanceId.toString(),
        ) + tokens

        val answer = redis.execute(ADMIT, listOf(waitingKey, QueueKeys.active(performanceId)), *args.toTypedArray())
            ?: return 0

        // 「몇 명」과 「얼마나 기다렸나」를 같이 받는다 — 줄 앞에서 뺀 사람의 진입 시각은 스크립트 안에만 있다.
        val admitted = answer.substringBefore('|').toLong()
        answer.substringAfter('|').split(',')
            .filter { it.isNotBlank() }
            .forEach { metrics.queueWaited(Duration.ofMillis(it.toLong())) }
        metrics.queueAdmitted(admitted)
        return admitted
    }

    /** 그 계정이 이미 들어와 있나. 브라우저를 껐다 켜도 자리를 안 잃는 자리다(`D12`) */
    fun tokenFor(performanceId: Long, accountId: Long): String? =
        redis.opsForValue().get(QueueKeys.admissionByAccount(performanceId, accountId))

    /**
     * 토큰이 가리키는 것. 없거나 만료면 null — 관문(23)이 이 값으로 계정·회차를 대조한다.
     * 만료를 여기서 안 센다. TTL 이 키를 지워서 **없는 것과 만료된 것이 같은 답**이다.
     */
    fun find(token: String): Admission? =
        redis.opsForValue().get(QueueKeys.admission(token))
            ?.let { objectMapper.readValue(it, Admission::class.java) }

    /**
     * 자리를 반납하고 토큰도 지운다. 줄을 떠났을 때(24) 부른다 —
     * **정원이 돌아야 뒷사람이 들어온다.** 반납이 없으면 TTL 10분 동안 빈 의자가 잠긴다.
     */
    fun release(performanceId: Long, accountId: Long) {
        val token = tokenFor(performanceId, accountId) ?: return
        redis.opsForZSet().remove(QueueKeys.active(performanceId), token)
        redis.delete(listOf(QueueKeys.admission(token), QueueKeys.admissionByAccount(performanceId, accountId)))
    }

    /**
     * 선점에 성공한 뒤 반납한다(23a). [release] 와 달리 **토큰 키를 안 지우고 [RETRY_GRACE] 로 줄이기만 한다.**
     *
     * 지우면 계약이 깨진다 — 응답을 못 받은 클라이언트가 같은 멱등키로 다시 오면 관문이 429 로 끊고,
     * `D4` 가 약속한 「저장된 본문을 그대로 돌려준다」가 관문 뒤에 있어 안 닿는다. 데이터는 안 깨지지만 계약이 어긋난다.
     *
     * **정원은 그대로 즉시 돈다.** 정원은 활성 집합이 세는 것이고 토큰 키는 관문이 대조하는 것뿐이라, 둘은 다른 자리다.
     *
     * 창을 넘긴 재시도는 줄에 다시 선다. 거기까지 덮으려면 관문이 멱등 저장소를 읽어야 하는데,
     * 그러면 대기열 층이 예매 층을 읽고 관문을 지나는 **모든** 요청에 조회가 하나 는다 — 그 값은 안 치른다.
     */
    fun releaseAfterHold(performanceId: Long, accountId: Long) {
        val token = tokenFor(performanceId, accountId) ?: return
        redis.execute(
            RELEASE_AFTER_HOLD,
            listOf(
                QueueKeys.active(performanceId),
                QueueKeys.admission(token),
                QueueKeys.admissionByAccount(performanceId, accountId),
            ),
            token,
            RETRY_GRACE.toMillis().toString(),
        )
    }

    /** 무작위 32바이트. 추측으로 남의 자리를 못 쓴다(`D9`) */
    private fun newToken(): String =
        ByteArray(TOKEN_BYTES).also(random::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    /** 토큰에 박힌 것(`D12`). 관문이 셋을 대조한다 — 토큰 하나로 남의 계정·다른 회차를 못 산다 */
    data class Admission(val accountId: Long, val performanceId: Long, val issuedAt: Long)

    companion object {
        /** 활성 정원 C — 시작값 2,000(ADR 0003). 「들어왔는데 안 사는 사람」이 쌓이는 것을 막는다. 31 이 조정한다 */
        const val CAPACITY = 2_000L

        /** 한 번에 들이는 수 M. [ADMIT_INTERVAL] 과 묶여 입장 속도 R = 100/5초 = 20/초가 된다(`D12` 「등식」) */
        const val BATCH_SIZE = 100L

        /** 스케줄러 주기. 이 값을 바꾸면 R 이 바뀌고 `D12` 의 등식을 다시 써야 한다 */
        const val ADMIT_INTERVAL = "PT5S"

        /** R — 등식의 좌변. [BATCH_SIZE] 와 [ADMIT_INTERVAL] 에서 나온다. 순번의 예상 대기가 이 값으로 나눈다 */
        const val ADMIT_PER_SECOND = BATCH_SIZE / 5

        /** 선점 5분 + 결제 여유(ADR 0003). 선점보다 짧으면 좌석을 쥔 채로 토큰이 죽는다 */
        val TOKEN_TTL: Duration = Duration.ofMinutes(10)

        /**
         * 반납한 토큰이 관문을 더 지날 수 있는 창(23a). 재시도가 이 안에 오면 저장된 201 을 받는다.
         * 45초는 흔한 읽기 타임아웃(30초)보다 길고, 사람이 화면을 다시 여는 시간보다는 짧다.
         *
         * 이 창 동안 그 사람은 관문을 지날 수 있지만 좌석은 못 늘린다 — 계정당 한 석 제한(15)이 먼저 막는다.
         */
        val RETRY_GRACE: Duration = Duration.ofSeconds(45)

        private const val TOKEN_BYTES = 32

        /**
         * 만료 정리 → 빈자리 계산 → 줄 앞에서 빼내기 → 토큰 저장까지 한 번에(`D12`).
         *
         * `admit:*` 키를 `KEYS` 가 아니라 접두로 받는다 — 토큰 이름을 스크립트가 만들어서 미리 못 적는다.
         * 단일 노드라 되는 짓이고, 클러스터로 가면 슬롯이 갈려 깨진다. 지금 클러스터 계획은 없다(ADR 0001).
         *
         * 시각은 `TIME` 이다. 앱이 주면 인스턴스 시계가 어긋난 만큼 토큰 수명이 들쭉날쭉해진다.
         */
        private val ADMIT = DefaultRedisScript<String>(
            """
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local capacity = tonumber(ARGV[1])
            local ttl = tonumber(ARGV[2])
            local tokenPrefix = ARGV[3]
            local accountPrefix = ARGV[4]
            local performanceId = ARGV[5]

            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
            local free = capacity - redis.call('ZCARD', KEYS[2])
            local wanted = #ARGV - 5
            if free < wanted then wanted = free end
            if wanted <= 0 then return '0|' end

            local popped = redis.call('ZPOPMIN', KEYS[1], wanted)
            local count = 0
            local waits = {}
            for i = 1, #popped, 2 do
                local account = popped[i]
                count = count + 1
                local token = ARGV[5 + count]
                redis.call('ZADD', KEYS[2], now + ttl, token)
                redis.call(
                    'SET',
                    tokenPrefix .. token,
                    cjson.encode({
                        account_id = tonumber(account),
                        performance_id = tonumber(performanceId),
                        issued_at = now
                    }),
                    'PX',
                    ttl
                )
                redis.call('SET', accountPrefix .. account, token, 'PX', ttl)
                -- 진입 시각이 score 라 지금과의 차가 곧 기다린 시간이다(30 의 `queue.wait_seconds`).
                waits[#waits + 1] = string.format('%d', now - tonumber(popped[i + 1]))
            end
            return count .. '|' .. table.concat(waits, ',')
            """.trimIndent(),
            String::class.java,
        )

        /**
         * 선점 성공 뒤의 반납(23a) — 활성에서 빼고, 계정 키를 지우고, **토큰 키는 줄이기만 한다.**
         *
         * 남은 TTL 이 유예보다 클 때만 줄인다. 같은 키로 여러 번 재시도해도 창이 뒤로 안 밀린다.
         */
        private val RELEASE_AFTER_HOLD = DefaultRedisScript<Long>(
            """
            redis.call('ZREM', KEYS[1], ARGV[1])
            redis.call('DEL', KEYS[3])
            local grace = tonumber(ARGV[2])
            local ttl = redis.call('PTTL', KEYS[2])
            if ttl > grace then
                redis.call('PEXPIRE', KEYS[2], grace)
            end
            return ttl
            """.trimIndent(),
            Long::class.java,
        )
    }
}
