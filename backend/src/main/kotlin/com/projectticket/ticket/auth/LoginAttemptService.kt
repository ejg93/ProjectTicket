package com.projectticket.ticket.auth

import java.time.Duration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

/**
 * 무차별 대입을 늦춘다(`3a`, `D9` — OWASP A07). ProjectShop `LoginAttemptService` 의 이식이다.
 *
 * **이메일과 IP 를 같이 센다.** 이메일만 세면 남의 계정을 잠글 수 있고(그 사람이 로그인을 못 한다),
 * IP 만 세면 공용 회선 하나가 통째로 막힌다. 둘을 묶으면 막히는 것은 **그 자리에서 그 계정을 두드리는 쪽**이다.
 *
 * **차단된 응답은 일반 실패와 같다**(`D9` 「계정 존재」). 「잠겼다」고 말하면 그 이메일이 가입돼 있다는 뜻이 된다.
 *
 * ProjectShop 은 Redis 가 죽으면 메모리 카운터로 넘어갔는데 **안 가져왔다.** 여기는 세션도 Redis 라
 * Redis 가 죽으면 로그인 자체가 안 된다 — 아무도 안 세는 경로가 없다.
 */
@Service
class LoginAttemptService(private val redis: StringRedisTemplate) {

    /** 세는 것은 실패뿐이다. 성공하면 [reset] 이 지운다 */
    fun isBlocked(email: String, ip: String): Boolean =
        (redis.opsForValue().get(key(email, ip))?.toIntOrNull() ?: 0) >= MAX_ATTEMPTS

    /** 첫 실패에만 TTL 을 건다. 매번 걸면 계속 두드리는 동안 잠금이 영원히 밀린다 */
    fun recordFailure(email: String, ip: String) {
        val key = key(email, ip)
        if (redis.opsForValue().increment(key) == 1L) {
            redis.expire(key, LOCK_DURATION)
        }
    }

    /** 맞는 비밀번호를 댄 사람은 더 셀 이유가 없다 */
    fun reset(email: String, ip: String) {
        redis.delete(key(email, ip))
    }

    /** 이메일은 대소문자를 안 가린다 — 가입도 그렇다(`account_email_key` 가 `lower(email)`) */
    private fun key(email: String, ip: String): String = "$KEY_PREFIX${email.lowercase()}:$ip"

    companion object {
        /** 이만큼 틀리면 막는다(ProjectShop 과 같은 값) */
        const val MAX_ATTEMPTS = 5

        /** 잠금이 저절로 풀리는 시간. 사람이 푸는 자리를 안 만든다 — 관리자 일이 늘고 사용자는 15분을 기다릴 수 있다 */
        val LOCK_DURATION: Duration = Duration.ofMinutes(15)

        private const val KEY_PREFIX = "login:fail:"
    }
}
