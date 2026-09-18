package com.projectticket.ticket.event

import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 좌석 현황의 판과 변경 로그(`D20` 의 Redis 절반, `10a`).
 *
 * 화면은 2초마다 묻는다. 그 물음이 전부 DB 로 가면 회차 하나에 초당 수천 번 2천 석을 읽는다 —
 * **버전을 Redis 가 세고**, 같은 버전의 스냅샷은 Redis 가 답한다. DB 를 읽는 것은 버전이 바뀐 직후 한 번뿐이다.
 *
 * **올리는 시점은 커밋 뒤다**(`D4` 「트랜잭션 경계」). 커밋 전에 올리면 롤백된 선점이 화면에 `H` 로 보인다.
 * 올리기가 실패하면(Redis 다운) 버전이 안 올라 폴링이 304 를 받는다 — **화면이 늦을 뿐 틀리지 않는다.**
 * 스냅샷은 언제나 DB 에서 만들기 때문이다.
 */
@Component
class SeatVersions(
    private val redis: StringRedisTemplate,
    private val jdbc: JdbcClient,
) {

    private val log = LoggerFactory.getLogger(SeatVersions::class.java)

    /**
     * 지금 판. 없으면 **DB 의 `max(updated_at)` 으로 씨를 뿌린다**(`SET NX`).
     *
     * 1 부터 시작하면 안 된다 — 클라이언트가 들고 있는 옛 `since`(마이크로초 epoch)가 새 버전보다 커서
     * 「변경 없음」으로 읽히고, 그 화면은 영영 안 따라온다. 10 이 쓰던 DB 대체값이 그 값이다.
     */
    fun current(performanceId: Long): Long {
        redis.opsForValue().get(key(performanceId))?.toLongOrNull()?.let { return it }

        val seed = databaseVersion(performanceId)
        redis.opsForValue().setIfAbsent(key(performanceId), seed.toString())
        return redis.opsForValue().get(key(performanceId))?.toLongOrNull() ?: seed
    }

    /**
     * 바뀐 좌석을 **커밋 뒤에** 알린다. 트랜잭션 밖에서 부르면 그 자리에서 올린다(트랜잭션이 이미 닫힌 스윕 같은 자리).
     *
     * 실패를 삼킨다 — 여기서 던지면 **이미 커밋된 예매가 예외로 보인다.** 화면이 늦는 것과 예매가 실패하는 것은 값이 다르다.
     */
    fun publishAfterCommit(rows: List<Row>, status: PerformanceSeatStatus) {
        if (rows.isEmpty()) return

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            bumpQuietly(rows, status)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = bumpQuietly(rows, status)
            },
        )
    }

    /**
     * `since` 뒤로 바뀐 좌석. 로그가 잘려 그 사이를 못 보여 주면 [Changes.truncated] 고, 부르는 쪽이 410 으로 답한다.
     *
     * 좌석 하나가 여러 번 바뀌었으면 **마지막 상태만** 남긴다. 화면이 그리는 것은 지금 모습이지 역사가 아니다.
     */
    fun changesSince(performanceId: Long, since: Long): Changes {
        val answer = redis.execute(CHANGES, listOf(key(performanceId), logKey(performanceId)), since.toString())
            ?: return Changes(current(performanceId), emptyList(), truncated = true)

        val versionPart = answer.substringBefore('|')
        val changePart = answer.substringAfter('|')
        val version = versionPart.toLong()
        if (changePart == GONE) return Changes(version, emptyList(), truncated = true)

        val latest = changePart.split(',')
            .filter { it.isNotBlank() }
            .associate { entry -> entry.substringBefore(':').toLong() to entry.substringAfter(':') }
        return Changes(version, latest.map { (id, state) -> Change(id, state) }, truncated = false)
    }

    /** 그 버전의 스냅샷. 버전이 키에 들어 있어 옛 그림이 새 버전으로 나갈 수가 없다 */
    fun cachedSnapshot(performanceId: Long, version: Long): String? =
        redis.opsForValue().get(snapshotKey(performanceId, version))

    /**
     * 같은 버전을 여럿이 동시에 만들 수 있다. 결과가 같아서 마지막 쓰기가 이기면 된다 — **락을 안 건다**(`D20`).
     * 락이 스냅샷을 만드는 것보다 비싸다.
     */
    fun cacheSnapshot(performanceId: Long, version: Long, json: String) {
        redis.opsForValue().set(snapshotKey(performanceId, version), json, SNAPSHOT_TTL)
    }

    private fun bumpQuietly(rows: List<Row>, status: PerformanceSeatStatus) {
        rows.groupBy { it.performanceId }.forEach { (performanceId, seats) ->
            try {
                // 씨를 먼저 뿌린다. 안 뿌리면 첫 쓰기가 버전을 1 로 만들어 클라이언트의 옛 `since` 보다 작아진다.
                current(performanceId)
                val args = listOf(LOG_MAX_LENGTH.toString()) +
                    seats.flatMap { listOf(it.performanceSeatId.toString(), status.letter) }
                redis.execute(BUMP, listOf(key(performanceId), logKey(performanceId)), *args.toTypedArray())
            } catch (e: RuntimeException) {
                // 다음 쓰기가 따라잡는다. 화면은 그때까지 한 판 늦다(`D20` 「장애」).
                log.warn("좌석 판을 못 올렸다 performance_id={} 이유={}", performanceId, e.javaClass.simpleName)
            }
        }
    }

    /** Redis 가 비었을 때 쓰는 값(`D20`). 좌석이 하나라도 바뀌면 느는 마이크로초 epoch 다 — `set_updated_at` 트리거가 올린다 */
    private fun databaseVersion(performanceId: Long): Long =
        jdbc.sql(
            """
            select coalesce((extract(epoch from max(updated_at)) * 1000000)::bigint, 0)
              from performance_seat
             where performance_id = :id
            """,
        ).param("id", performanceId).query(Long::class.java).single()

    private fun key(performanceId: Long) = "seat:ver:$performanceId"

    private fun logKey(performanceId: Long) = "seat:log:$performanceId"

    private fun snapshotKey(performanceId: Long, version: Long) = "seat:snap:$performanceId:$version"

    /** 좌석 UPDATE 의 `returning performance_seat_id, performance_id` 를 그대로 받는다 */
    data class Row(val performanceSeatId: Long, val performanceId: Long)

    /** 좌석 하나의 지금 상태. `s` 가 한 글자인 이유는 크기다 — 2천 석 × 폴링이라 필드 이름이 곧 대역폭이다(`D20`) */
    data class Change(val id: Long, val s: String)

    data class Changes(val version: Long, val changes: List<Change>, val truncated: Boolean)

    companion object {
        /** 변경 로그의 대략 길이. 넘으면 오래된 것부터 잘리고, 잘린 구간을 물으면 410 이다 */
        const val LOG_MAX_LENGTH = 10_000

        /** 스냅샷 수명. 짧은 이유는 **버전이 바뀌면 키가 달라져서** 오래 둘 값이 아니어서다 */
        val SNAPSHOT_TTL: Duration = Duration.ofSeconds(10)

        private const val GONE = "gone"

        /**
         * 판을 올리고 바뀐 좌석을 로그에 적는다. **스트림 id 를 버전으로 쓴다** — 그러면 `since` 로 자르는 것이
         * 범위 조회 하나가 된다.
         */
        private val BUMP = DefaultRedisScript<Long>(
            """
            local v = redis.call('INCR', KEYS[1])
            -- **정수를 %d 로 박는다.** 그냥 이으면 Lua 가 큰 수를 지수 표기(1.78e+15)로 접어 스트림 id 가 깨진다.
            local version = string.format('%d', v)
            local changed = (#ARGV - 1) / 2
            for i = 1, changed do
                redis.call(
                    'XADD', KEYS[2], 'MAXLEN', '~', ARGV[1], version .. '-' .. i,
                    'id', ARGV[2 * i], 's', ARGV[2 * i + 1]
                )
            end
            return v
            """.trimIndent(),
            Long::class.java,
        )

        /**
         * 「버전|id:상태,id:상태」 또는 「버전|gone」 을 돌려준다.
         *
         * 문자열인 이유는 중첩 배열을 Kotlin 으로 받으면 캐스팅이 늘어서다 — 스크립트가 한 줄로 접는 편이 읽기 쉽다.
         */
        private val CHANGES = DefaultRedisScript<String>(
            """
            local ver = tonumber(redis.call('GET', KEYS[1]) or '0')
            local since = tonumber(ARGV[1])
            -- 지수 표기로 접히지 않게 %d 로 박는다(BUMP 와 같은 이유).
            local head = string.format('%d', ver) .. '|'
            if since >= ver then return head end

            local first = redis.call('XRANGE', KEYS[2], '-', '+', 'COUNT', 1)
            if #first == 0 then return head .. 'gone' end
            local firstVersion = tonumber(string.match(first[1][1], '^(%d+)'))
            if since + 1 < firstVersion then return head .. 'gone' end

            local entries = redis.call('XRANGE', KEYS[2], string.format('%d', since + 1) .. '-0', '+')
            local out = {}
            for i = 1, #entries do
                local fields = entries[i][2]
                out[#out + 1] = fields[2] .. ':' .. fields[4]
            end
            return head .. table.concat(out, ',')
            """.trimIndent(),
            String::class.java,
        )
    }
}
