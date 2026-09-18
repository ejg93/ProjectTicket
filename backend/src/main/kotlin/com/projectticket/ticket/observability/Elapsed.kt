package com.projectticket.ticket.observability

/**
 * 잰 시간을 ms 로(`D10`). 로그와 지표가 쓰는 단위가 ms 다.
 *
 * **나누는 수를 자리마다 적지 않는다.** 세 군데서 적으면 한 군데가 마이크로가 되고, 그 로그만 조용히 천 배로 보인다.
 *
 * `System.nanoTime` 을 쓰는 이유는 벽시계가 아니라 **경과**를 재기 때문이다 — 시계가 뒤로 가도 이 값은 안 뒤집힌다.
 */
fun elapsedMillis(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI

private const val NANOS_PER_MILLI = 1_000_000L
