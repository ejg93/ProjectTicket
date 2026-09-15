package com.projectticket.ticket.payment

import com.projectticket.ticket.idempotency.IdempotencyService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 예매 하나를 결제한다. 트랜잭션 셋(`D4` 「트랜잭션 경계」):
 *
 * ```
 * ① held → paying          (짧다. PaymentTransitionService.startPaying)
 * ② PG 호출                 (트랜잭션 없음. 응답을 기다리는 동안 좌석 행 잠금이 열려 있으면 안 된다)
 * ③ 결과 반영               (짧다. 멱등 트랜잭션 안 — 결제 행 + 예매 상태 + 응답 저장)
 * ```
 *
 * **`@Transactional` 이 없어야 한다.** 이 메서드 안에서 PG 를 부른다.
 *
 * 멱등을 컨트롤러가 아니라 여기서 감싼다 — 순서가 「① → PG → ③」이고 PG 가 트랜잭션 밖이어야 해서 그 순서를 아는 쪽이 감싼다.
 * 재생 확인이 ① 보다 앞이다. 재전송 시점에는 그 예매가 이미 `reserved` 라 순서를 바꾸면 재전송이 「낼 수 없는 예매」로 막힌다.
 *
 * **무응답은 재시도가 아니라 상태 조회다**(`D4` 「재시도」). 승인됐는데 응답만 못 받았을 수 있고, 재시도는 이중 결제다.
 */
@Service
class PaymentService(
    private val gateway: MockPaymentGateway,
    private val transitions: PaymentTransitionService,
    private val idempotency: IdempotencyService,
) {

    private val log = LoggerFactory.getLogger(PaymentService::class.java)

    /** 금액이 없다. 낼 돈은 예매에 박제돼 있어서 서버가 읽는다 — 받으면 그 값이 맞는지 검사하는 코드가 따로 필요해진다 */
    data class Command(val reservationId: Long, val cardNumber: String) {
        override fun toString(): String = "Command[reservationId=$reservationId]"
    }

    /**
     * 결제 결과. 거절·실패도 **201 의 본문**이다(`D5` 「바깥이 거절한 것은 4xx 가 아니다」).
     * 멱등 재생이 이것을 저장했다 되살리므로 필드를 빼지 않는다.
     */
    data class Result(
        val paymentId: Long,
        val reservationId: Long,
        val status: String,
        val amount: Int,
        val approvalNumber: String?,
        val declineReason: String?,
        val cardLast4: String,
        val reservationStatus: String,
    )

    /** 멱등 본문 비교용. 카드번호는 해시에만 들어가고 저장되지 않는다 */
    private data class Fingerprint(val reservationId: Long, val cardNumber: String)

    fun pay(accountId: Long, idempotencyKey: String, command: Command): Result {
        val fingerprint = Fingerprint(command.reservationId, command.cardNumber)
        idempotency.replayIfPresent(accountId, idempotencyKey, fingerprint, Result::class.java)?.let { return it }

        val paying = transitions.startPaying(accountId, command.reservationId)
        val verdict = askGateway(idempotencyKey, paying, command.cardNumber)

        return idempotency.run(accountId, idempotencyKey, fingerprint, Result::class.java) {
            val settled = transitions.settle(accountId, paying, verdict)
            Result(
                paymentId = settled.paymentId,
                reservationId = paying.reservationId,
                status = if (verdict == null) PaymentStatus.FAILED.code else if (verdict.approved) PaymentStatus.APPROVED.code else PaymentStatus.DECLINED.code,
                amount = paying.amount,
                approvalNumber = verdict?.approvalNumber,
                declineReason = if (verdict == null) PaymentTransitionService.NO_RESPONSE else verdict.declineReason,
                cardLast4 = verdict?.cardLast4 ?: PaymentTransitionService.UNKNOWN_LAST4,
                reservationStatus = settled.reservationStatus,
            ).also { if (settled.late) cancelLateApproval(verdict) }
        }
    }

    /**
     * ② PG 호출. 결과가 없으면(무응답) 같은 키로 **상태를 묻는다.** PG 가 모르면 null — `failed` 로 적는다.
     *
     * @return null 이면 「응답이 없었고 PG 도 모른다」
     */
    private fun askGateway(idempotencyKey: String, paying: PaymentTransitionService.Paying, cardNumber: String): MockPaymentGateway.Result? {
        val started = System.nanoTime()
        val verdict = try {
            gateway.approve(MockPaymentGateway.Request(idempotencyKey, paying.amount, cardNumber))
        } catch (e: MockPaymentGateway.TimedOut) {
            gateway.inquire(idempotencyKey)
        }
        // 외부 호출 한 줄(`D10`) — 대상·ms·결과. 카드는 안 찍는다.
        log.info(
            "mock-pg approve reservation_id={} ms={} result={}",
            paying.reservationId,
            (System.nanoTime() - started) / 1_000_000,
            verdict?.let { if (it.approved) "approved" else "declined" } ?: "no_response",
        )
        return verdict
    }

    /**
     * 승인이 늦어 좌석을 못 줬다 — PG 에 취소를 보낸다. 실물 PG 라면 자동 환불 자리다(`D4`).
     * 멱등 트랜잭션 안에서 부르는 것은 PG 호출을 트랜잭션에 넣는 일이지만, 취소는 드물고 짧으며 실패해도 감사 `payment.late` 와 환불(17)이 받는다.
     */
    private fun cancelLateApproval(verdict: MockPaymentGateway.Result?) {
        // 승인번호 없이 late 일 수 없다 — late 는 승인일 때만 계산된다(`PaymentTransitionService.settle`).
        gateway.cancel(verdict!!.approvalNumber!!)
    }
}
