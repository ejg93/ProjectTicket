package com.projectticket.ticket.payment

import com.projectticket.ticket.idempotency.IdempotencyService
import com.projectticket.ticket.observability.elapsedMillis
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
 * **① 이 키 선점보다 앞이라** 앞 요청이 PG 를 기다리는 동안 온 재전송은 `idempotency-in-progress` 가 아니라 ① 의 0행 → `invalid-transition`(from=paying) 을 받는다(`D4` 「서버가 하는 일」).
 *
 * **무응답은 재시도가 아니라 상태 조회다**(`D4` 「재시도」). 승인됐는데 응답만 못 받았을 수 있고, 재시도는 이중 결제다.
 * 승인이 늦어 좌석을 못 준 것(`payment_late`)은 여기서 되돌리지 않는다 — 환불 행(17b)이 되돌린다. PG 호출을 트랜잭션 안에 넣지 않으려는 것이다.
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

    /**
     * 멱등 본문 비교용(`I16-6`). **카드번호 칸이 없다** — 무키 해시로 24시간 남는 자리라 전체 번호를 넣으면 `card_last4` 와 함께
     * 역산이 쉽다. 끝 4자리가 같은 다른 카드는 못 가르는 것을 받아들였다(2026-11-12 사용자). 구분자만 다른 같은 카드는 같은 지문이다.
     */
    private data class Fingerprint(val reservationId: Long, val cardLast4: String)

    fun pay(accountId: Long, idempotencyKey: String, command: Command): Result {
        val fingerprint = Fingerprint(command.reservationId, CardNumbers.last4Of(command.cardNumber))
        idempotency.replayIfPresent(accountId = accountId, key = idempotencyKey, request = fingerprint, responseType = Result::class.java)?.let { return it }

        val paying = transitions.startPaying(accountId, command.reservationId, idempotencyKey)
        val verdict = askGateway(paying, command.cardNumber)
        // 응답이 없어도 카드는 안다 — 요청이 들고 있다. 실패 행에도 뒷 4자리를 적는다.
        val cardLast4 = verdict?.cardLast4 ?: fingerprint.cardLast4

        return idempotency.run(accountId = accountId, key = idempotencyKey, request = fingerprint, responseType = Result::class.java) {
            val settled = transitions.settle(accountId = accountId, paying = paying, verdict = verdict, cardLast4 = cardLast4)
            Result(
                paymentId = settled.paymentId,
                reservationId = paying.reservationId,
                status = if (verdict == null) PaymentStatus.FAILED.code else if (verdict.approved) PaymentStatus.APPROVED.code else PaymentStatus.DECLINED.code,
                amount = paying.amount,
                approvalNumber = verdict?.approvalNumber,
                declineReason = if (verdict == null) PaymentTransitionService.NO_RESPONSE else verdict.declineReason,
                cardLast4 = cardLast4,
                reservationStatus = settled.reservationStatus,
            )
        }
    }

    /**
     * ② PG 호출. 결과가 없으면(무응답) 같은 키로 **상태를 묻는다.** PG 가 모르면 null — `failed` 로 적는다.
     *
     * @return null 이면 「응답이 없었고 PG 도 모른다」
     */
    private fun askGateway(paying: PaymentTransitionService.Paying, cardNumber: String): MockPaymentGateway.Result? {
        // PG 키는 ① 이 시도 행에 적은 그 값이다(`PaymentTransitionService.gatewayKeyOf`, `I17-2`) — 대사가 같은 키로 묻는다.
        val gatewayKey = paying.gatewayKey
        val started = System.nanoTime()
        val verdict = try {
            gateway.approve(MockPaymentGateway.Request(gatewayKey, paying.amount, cardNumber))
        } catch (e: MockPaymentGateway.TimedOut) {
            gateway.inquire(gatewayKey)
        }
        // 외부 호출 한 줄(`D10`) — 대상·ms·결과. 카드는 안 찍는다.
        log.info(
            "mock-pg approve reservation_id={} ms={} result={}",
            paying.reservationId,
            elapsedMillis(started),
            verdict?.let { if (it.approved) "approved" else "declined" } ?: "no_response",
        )
        return verdict
    }
}
