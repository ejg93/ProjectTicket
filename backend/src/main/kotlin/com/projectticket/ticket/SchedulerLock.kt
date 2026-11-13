package com.projectticket.ticket

import com.projectticket.ticket.error.isRedisDown
import java.time.Duration
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

/**
 * 인스턴스가 여럿일 때 스케줄러를 하나만 돌린다(33, `D4` — ADR 0003 의 「스케줄러 중복 방지」).
 *
 * **락은 정확성이 아니라 절약이다.** 스윕·종료·입장은 전부 조건부 UPDATE 나 Lua 라 두 대가 같이 돌아도 결과가 같다 —
 * 다만 같은 행을 두 번 훑는다. 그것을 막는 것이 이 락이다. **겹침만 막고 횟수는 안 막는다** — 일이 끝나면 바로 놓아서
 * 대마다 제 주기로 한 번씩 돈다. 횟수가 뜻을 갖는 입장은 주기를 Redis 에 따로 둔다(`AdmissionService`, `35f`).
 *
 * **Redisson 을 안 쓴다**(사용자 선택, ADR 0003 을 그 근거와 함께 고쳤다). 재진입·watchdog 이 필요 없고 —
 * 한 번에 한 회만 돌면 되고 실패하면 다음 회가 있다 — Netty 기반 클라이언트를 Lettuce 옆에 하나 더 띄울 값이 아니다.
 *
 * **해제는 내 토큰일 때만 한다.** 일이 TTL 보다 길어지면 락이 이미 남에게 넘어갔을 수 있고,
 * 그때 무조건 `DEL` 하면 **남이 일하는 중에 락을 뺏는다** — 그 뒤로는 셋이 같이 돈다.
 */
@Component
class SchedulerLock(private val redis: StringRedisTemplate) {

    private val log = LoggerFactory.getLogger(SchedulerLock::class.java)

    /**
     * 락을 잡으면 [work] 를 돌리고 결과를 준다. 남이 쥐고 있으면 **아무 일도 안 하고 null** 이다 —
     * 기다리지 않는다. 기다리면 다음 회와 겹쳐서 큐가 밀린다.
     */
    fun <T> runExclusively(name: String, ttl: Duration = DEFAULT_TTL, work: () -> T): T? {
        val key = "lock:$name"
        val token = UUID.randomUUID().toString()
        // **Redis 가 죽으면 락 없이 돈다**(`I17-5`). 락은 절약이라 겹쳐도 결과가 같고, 던지면 DB 만 쓰는 선점 만료가 Redis 장애 내내 선다.
        val taken = try {
            redis.opsForValue().setIfAbsent(key, token, ttl) == true
        } catch (e: RuntimeException) {
            if (!e.isRedisDown()) throw e
            log.warn("Redis 가 안 받아 스케줄러를 락 없이 돌린다 name={}", name, e)
            return work()
        }
        if (!taken) {
            log.debug("스케줄러 락을 남이 쥐고 있다 name={}", name)
            return null
        }

        return try {
            work()
        } finally {
            release(name, key, token)
        }
    }

    /** 일하는 사이 Redis 가 죽었으면 놓기도 실패한다 — 그것이 일의 결과를 덮으면 안 된다. 락은 TTL 이 지운다 */
    private fun release(name: String, key: String, token: String) {
        try {
            redis.execute(RELEASE, listOf(key), token)
        } catch (e: RuntimeException) {
            if (!e.isRedisDown()) throw e
            log.warn("Redis 가 안 받아 락을 못 놓았다 — TTL 이 지운다 name={}", name, e)
        }
    }

    companion object {
        /**
         * 락의 수명. 잡은 채로 죽어도 이만큼 뒤에는 남이 잡는다 —
         * 스케줄러 주기(5초~1분)보다 넉넉하고, 한 회가 이보다 오래 걸리면 그 자체가 신호다(30 의 지표).
         */
        val DEFAULT_TTL: Duration = Duration.ofSeconds(30)

        /** 내 토큰일 때만 지운다. `GET` 뒤 `DEL` 을 따로 하면 그 사이에 TTL 이 끝나 남이 잡은 락을 지운다 */
        private val RELEASE = DefaultRedisScript<Long>(
            """
                if redis.call('GET', KEYS[1]) == ARGV[1] then
                    return redis.call('DEL', KEYS[1])
                end
                return 0
            """.trimIndent(),
            Long::class.java,
        )
    }
}
