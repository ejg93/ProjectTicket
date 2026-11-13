package com.projectticket.ticket.error

import io.lettuce.core.RedisBusyException
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisException
import io.lettuce.core.RedisLoadingException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.RedisSystemException

/**
 * 원인 사슬에 Redis 장애가 있나 — 503 `queue-unavailable` 로 낼지 가르는 판정 하나(`I16-4`, 마무리 34차 독립 리뷰 I1).
 *
 * 연결 실패만 보면 뜬 뒤에 죽은 Redis 를 놓친다 — 그때는 Lettuce 명령 시간 초과가 Spring `QueryTimeoutException` 에
 * 감싸여 온다. `QueryTimeoutException` 자체로 가르면 DB 시간 초과까지 503 이 되니 **원인의 Lettuce 예외**를 본다.
 * [ProblemFallbackFilter] 와 [ApiExceptionHandler] 가 같이 부른다 — 둘이 갈리면 한 장애에 답이 둘이다. `SchedulerLock` 도 이것 하나다.
 *
 * **서버가 오류로 답한 것은 장애가 아니다**(`I17-10`). `RedisCommandExecutionException`(스크립트 오류·`WRONGTYPE`)은 우리 버그라
 * 장애로 보면 락은 `RELEASE` 버그를 WARN 으로 삼키고 HTTP 는 버그를 503 으로 낸다. 다만 그 하위인 `BUSY`·`LOADING` 과
 * 쓰기를 거부하는 답(`MISCONF` 디스크·`OOM` 메모리·`READONLY` 복제본)은 서버가 지금 못 받는다는 뜻이라 장애다(마무리 36차) —
 * 버그로 보면 락이 던져 DB 만 쓰는 선점 만료까지 선다(`I17-5`).
 */
fun Throwable.isRedisDown(): Boolean {
    val chain = generateSequence(this) { it.cause }.toList()
    return when {
        chain.any { it is RedisBusyException || it is RedisLoadingException || it.refusesWrites() } -> true
        chain.any { it is RedisCommandExecutionException } -> false
        else -> chain.any { it is RedisConnectionFailureException || it is RedisSystemException || it is RedisException }
    }
}

/** 서버가 쓰기를 거부한 답. Lettuce 가 따로 가르지 않아 응답 머리말로 본다 */
private fun Throwable.refusesWrites(): Boolean =
    this is RedisCommandExecutionException && WRITE_REFUSALS.any { message.orEmpty().startsWith(it) }

private val WRITE_REFUSALS = listOf("MISCONF", "OOM", "READONLY")
