package com.projectticket.ticket.error

import io.lettuce.core.RedisException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.RedisSystemException

/**
 * 원인 사슬에 Redis 장애가 있나 — 503 `queue-unavailable` 로 낼지 가르는 판정 하나(`I16-4`, 마무리 34차 독립 리뷰 I1).
 *
 * 연결 실패만 보면 뜬 뒤에 죽은 Redis 를 놓친다 — 그때는 Lettuce 명령 시간 초과가 Spring `QueryTimeoutException` 에
 * 감싸여 온다. `QueryTimeoutException` 자체로 가르면 DB 시간 초과까지 503 이 되니 **원인의 Lettuce 예외**를 본다.
 * [ProblemFallbackFilter] 와 [ApiExceptionHandler] 가 같이 부른다 — 둘이 갈리면 한 장애에 답이 둘이다.
 */
fun Throwable.isRedisDown(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is RedisConnectionFailureException || it is RedisSystemException || it is RedisException
    }
