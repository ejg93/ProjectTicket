package com.projectticket.ticket.notification

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 메일을 보내는 흉내를 낸다. ProjectShop 것을 Kotlin 으로 옮겼다 — 로컬 전용이라 실제로 나가는 곳이 없다(`D11` 「채널은 이메일 하나」).
 *
 * **인터페이스를 안 둔다.** [com.projectticket.ticket.payment.MockPaymentGateway] 와 같은 판단이다 — 갈아끼울 것이 실물 SMTP 하나뿐이라
 * 「부르는 곳을 세고 만든다」에 걸린다(`CLAUDE.md` 대전제 3). 배포가 생겨 구현이 둘이 되는 날 그 자리에서 인터페이스를 판다.
 *
 * **주소도 본문도 로그에 안 넣는다**(`D10`). 남기는 것은 결과와 길이뿐이고, 무엇을 보냈는지는 `notification.body` 가 든다.
 */
@Component
class MockNotificationSender {

    private val log = LoggerFactory.getLogger(MockNotificationSender::class.java)

    /** @param failureReason 못 나갔으면 종류. 나갔으면 null */
    data class Result(val succeeded: Boolean, val failureReason: String?) {
        companion object {
            fun sent() = Result(true, null)
            fun failed(reason: String) = Result(false, reason)
        }
    }

    /** 주소가 `@bounce.invalid` 로 끝나면 실패한다 — 실패 경로가 실제로 도는지 보는 자리다(카드번호가 결과를 정하는 것과 같은 방식) */
    fun send(address: String, subject: String, body: String): Result {
        if (address.endsWith(FAILING_DOMAIN)) {
            log.info("모의 발송 실패 이유={}", FAILURE_REASON)
            return Result.failed(FAILURE_REASON)
        }

        log.info("모의 발송 성공 제목길이={} 본문길이={}", subject.length, body.length)
        return Result.sent()
    }

    companion object {
        const val FAILING_DOMAIN = "@bounce.invalid"

        /** 받는 곳이 없다는 뜻 */
        const val FAILURE_REASON = "unknown_recipient"

        /** 탈퇴한 계정이라 보낼 곳이 없다. 다시 해도 같아서 재시도가 아니라 `skipped` 다 */
        const val WITHDRAWN_REASON = "withdrawn_account"
    }
}
