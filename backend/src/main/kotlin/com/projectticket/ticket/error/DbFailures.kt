package com.projectticket.ticket.error

import java.sql.SQLException
import java.sql.SQLTransientConnectionException

/**
 * 원인 사슬에 DB 시간 상한이 있나 — 503 `service-busy` 로 낼지 가르는 판정 하나(`I17-6`).
 *
 * 잠금 대기 초과(`55P03`, `lock_timeout` 3초)·문장 시간 초과(`57014`, `statement_timeout` 10초)·풀 대기 초과
 * (`SQLTransientConnectionException`, Hikari `connection-timeout` 3초)다. **SQLSTATE 를 직접 본다** — Spring 의 번역 예외 종류는
 * 드라이버·판마다 갈린다(`stack.md`, 교착 재시도와 같은 이유). 교착(`40P01`)은 재시도 대상이라 여기 안 든다.
 * [ProblemFallbackFilter] 와 [ApiExceptionHandler] 가 같이 부른다 — 둘이 갈리면 한 상한에 답이 둘이다.
 */
fun Throwable.isDbBusy(): Boolean =
    generateSequence(this) { it.cause }.any { it is SQLTransientConnectionException } || hitStatementLimit()

/**
 * 원인 사슬에 문장 상한(잠금 대기 `55P03`·문장 시간 `57014`)이 있나. [isDbBusy] 와 소비자 재시도(`ConsumerErrorHandling`, `I17-12`)가
 * 같은 집합을 쓴다 — `55` 는 Spring 이 번역하지 못하고 `57` 은 판마다 번역이 갈려 이름으로는 못 가른다.
 */
fun Throwable.hitStatementLimit(): Boolean =
    generateSequence(this) { it.cause }.any { it is SQLException && it.sqlState in BUSY_SQL_STATES }

private val BUSY_SQL_STATES = setOf("55P03", "57014")
