package com.projectticket.ticket

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
 * 다만 같은 행을 두 번 훑고, 입장은 설정한 속도 R 의 대수배로 사람을 들인다. 그 둘을 막는 것이 이 락이다.
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
        val taken = redis.opsForValue().setIfAbsent(key, token, ttl) == true
        if (!taken) {
            log.debug("스케줄러 락을 남이 쥐고 있다 name={}", name)
            return null
        }

        return try {
            work()
        } finally {
            redis.execute(RELEASE, listOf(key), token)
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
