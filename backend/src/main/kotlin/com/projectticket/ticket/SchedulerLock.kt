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
 * **락은 대개 정확성이 아니라 절약이다.** 스윕·종료·입장은 전부 조건부 UPDATE 나 Lua 라 두 대가 같이 돌아도 결과가 같다 —
 * 다만 같은 행을 두 번 훑는다. 그것을 막는 것이 이 락이다. **알림 발송은 예외다** — 겹치면 같은 메일이 대수만큼 나간다(`I17-5`).
 * 그래서 Redis 가 죽었을 때 무엇을 할지를 부르는 쪽이 [OnOutage] 로 고른다. **겹침만 막고 횟수는 안 막는다** — 일이 끝나면 바로 놓아서
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
    fun <T> runExclusively(name: String, ttl: Duration = DEFAULT_TTL, onOutage: OnOutage = OnOutage.RUN_UNLOCKED, work: () -> T): T? {
        val key = "lock:$name"
        val token = UUID.randomUUID().toString()
        // **Redis 가 죽으면 기본은 락 없이 돈다**(`I17-5`). 던지면 DB 만 쓰는 선점 만료가 Redis 장애 내내 선다.
        val taken = try {
            redis.opsForValue().setIfAbsent(key, token, ttl) == true
        } catch (e: RuntimeException) {
            if (!e.isRedisDown()) throw e
            // 스택은 안 찍는다 — 장애 동안 스케줄러 여섯 × 대수 × 주기만큼 찍힌다. 원인 한 줄이면 충분하다.
            log.warn("Redis 가 안 받는다 name={} 처리={} 원인={}", name, onOutage, e.toString())
            if (onOutage == OnOutage.SKIP) return null
            // 시간 초과로 빠진 `SET NX` 가 Redis 에 뒤늦게 들어갔을 수 있다 — 놓지 않으면 그 락이 TTL 동안 모두를 막는다. 해제는 토큰 대조라 안 들어갔으면 0 이다.
            return try {
                work()
            } finally {
                release(name, key, token)
            }
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
            log.warn("Redis 가 안 받아 락을 못 놓았다 — TTL 이 지운다 name={} 원인={}", name, e.toString())
        }
    }

    /** Redis 가 죽어 락을 못 잡았을 때 */
    enum class OnOutage {
        /** 락 없이 돈다. 겹쳐도 결과가 같은 일(조건부 UPDATE·Lua) */
        RUN_UNLOCKED,

        /** 이번 회를 건너뛴다. 겹치면 결과가 달라지는 일(메일 발송) — Redis 가 돌아온 뒤 나간다 */
        SKIP,
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
