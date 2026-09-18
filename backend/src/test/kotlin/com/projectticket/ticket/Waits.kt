package com.projectticket.ticket

import java.time.Duration

/**
 * 브로커를 거치면 「보냈다」와 「받았다」 사이에 시간이 있다(28). 그 사이를 기다리는 자리.
 *
 * **`Thread.sleep` 을 안 쓴다.** 고정 대기는 빠른 기계에서 느리고 느린 기계에서 깨진다 —
 * 조건이 맞는 즉시 돌아오고, 안 맞으면 시한까지 기다렸다가 **마지막 상태를 그대로 들고 실패한다.**
 */
object Waits {

    private val DEFAULT: Duration = Duration.ofSeconds(15)
    private val INTERVAL: Duration = Duration.ofMillis(100)

    /** 조건이 참이 될 때까지. 시한을 넘기면 [AssertionError] 다 */
    fun until(what: String, timeout: Duration = DEFAULT, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(INTERVAL.toMillis())
        }
        throw AssertionError("$timeout 안에 안 됐다: $what")
    }

    /** 값이 나올 때까지. 목록이 빌 때 `single()` 이 터지는 자리를 이것으로 감싼다 */
    fun <T : Any> value(what: String, timeout: Duration = DEFAULT, supplier: () -> T?): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            supplier()?.let { return it }
            Thread.sleep(INTERVAL.toMillis())
        }
        throw AssertionError("$timeout 안에 안 왔다: $what")
    }
}
