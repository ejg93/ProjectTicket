package com.projectticket.ticket.payment

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 진짜 PG 자리에 서 있는 모의 결제사. ProjectShop 것을 옮기면서 결제 수단을 카드 하나로 줄이고(ADR 0003) **상태 조회**를 더했다.
 *
 * 흉내내는 것은 승인 여부가 아니라 **계약**이다. 결과를 무작위로 내면 테스트가 흔들리고 늘 승인하면 거절 경로가 한 번도 안 돈다.
 * 그래서 결과를 카드번호 뒷 4자리로 가른다(ADR 0003 「모의 결제 실패」) — 화면에서 손으로 밟을 때도 번호만 바꾸면 된다.
 *
 * | 뒷 4자리 | 결과 |
 * |---|---|
 * | `0000` | 거절(`insufficient_funds`) |
 * | `0001` | 무응답. PG 에 아무것도 안 남는다 → 상태 조회도 비어 있다 → `failed` |
 * | `0002` | 지연. **승인은 났는데 응답만 잃었다** → 상태 조회가 승인을 돌려준다 |
 * | 그 밖 | 승인 |
 *
 * **멱등키를 받는 것이 핵심 계약이다.** PG 호출이 트랜잭션 밖이라(`D4`) 재전송이 같은 카드에 두 번 승인을 낼 구간이 열리고,
 * 그 구간을 닫는 것은 PG 가 같은 키에 같은 결과를 주는 것이다. 무응답에 재시도하지 않고 [inquire] 로 묻는 이유도 같다(`D4` 「재시도」).
 *
 * 기억은 메모리라 재기동하면 사라진다. 알고 남기는 구멍이다 — 모의 결제사에 표를 파면 우리 DB 가 남의 시스템 상태를 들게 된다.
 * 우리 쪽 중복은 `payment_approved_idx` 가 끝에서 한 번 더 막는다.
 */
@Component
class MockPaymentGateway {

    /** 카드번호는 여기까지만 온다. 이 값은 저장되지 않고 우리 표 어디에도 안 닿는다(`D9`). 남는 것은 [Result.cardLast4] 뿐이다 */
    data class Request(val idempotencyKey: String, val amount: Int, val cardNumber: String) {
        /** 카드번호를 로그·디버거에 안 찍는다(`D10`) */
        override fun toString(): String = "Request[idempotencyKey=$idempotencyKey, amount=$amount]"
    }

    /** @param approvalNumber 승인번호. 거절이면 null. @param declineReason 거절 사유. 승인이면 null */
    data class Result(val approvalNumber: String?, val cardLast4: String, val declineReason: String?) {
        val approved: Boolean get() = approvalNumber != null
    }

    /** 결제사가 제때 답을 안 줬다. `ErrorCode` 가 아니다 — 밖으로 안 나가고 [PaymentService] 가 상태 조회로 푼다 */
    class TimedOut(message: String) : RuntimeException(message)

    private val random = SecureRandom()

    /** 같은 키에 같은 답을 주기 위한 기억. 지연 카드는 응답을 잃어도 여기엔 남는다 — 그것이 「승인은 났는데 응답만 못 받았다」다 */
    private val byKey = ConcurrentHashMap<String, Result>()

    /**
     * 승인을 요청한다.
     *
     * @throws TimedOut 응답이 없을 때. 같은 키로 [inquire] 하는 것이 안전하다
     * @throws IllegalArgumentException 카드번호 형식이 아닐 때. 입구가 이미 거르므로 여기 오면 우리 잘못이다
     */
    fun approve(request: Request): Result {
        byKey[request.idempotencyKey]?.let { return it }

        val digits = digitsOf(request.cardNumber)
        val last4 = digits.takeLast(4)
        return when (last4) {
            DECLINE_LAST4 -> Result(null, last4, DECLINE_REASON).also { byKey[request.idempotencyKey] = it }
            SILENT_LAST4 -> throw TimedOut("결제사가 응답하지 않는다")
            DELAYED_LAST4 -> {
                byKey[request.idempotencyKey] = approved(last4)
                throw TimedOut("승인은 났는데 응답이 늦었다")
            }
            else -> approved(last4).also { byKey[request.idempotencyKey] = it }
        }
    }

    /** 상태 조회. 무응답 뒤에 **재시도 대신** 부른다 — 재시도는 이중 결제다(`D4`). PG 가 모르면 null */
    fun inquire(idempotencyKey: String): Result? = byKey[idempotencyKey]

    /** @param refundNumber PG 가 채번한 환불 거래번호 */
    data class RefundResult(val refundNumber: String)

    /** 이미 돌려준 환불. 키는 우리 환불 id 다 */
    private val refundsByKey = ConcurrentHashMap<String, RefundResult>()

    /**
     * 승인된 결제의 전부 또는 일부를 돌려준다. **거절이 없다** — 승인은 카드 한도 판정이라 거절이 정상 결과지만, 환불은 이미 받은 돈을
     * 돌려주는 것이라 결제사가 거부할 사유가 없다. 상한 검사는 우리 쪽(`refund_amounts_check_trigger`)이다.
     *
     * 멱등키는 우리 환불 id 다. 승인과 같은 이유로 필요하다 — 호출이 트랜잭션 밖이라 재시도가 두 번 환불할 구간이 열리고, PG 가 같은 키에 같은 답을 줘서 닫는다.
     */
    fun refund(refundKey: String, amount: Int): RefundResult {
        require(amount >= 0) { "환불 금액이 음수다" }
        return refundsByKey.computeIfAbsent(refundKey) { RefundResult("MR%d%04d".format(System.currentTimeMillis(), random.nextInt(SERIAL_BOUND))) }
    }

    private fun approved(last4: String) = Result(issueApprovalNumber(), last4, null)

    /** 시각을 앞에 둬서 재기동해도 안 겹친다 — 일련번호만 쓰면 다시 뜬 뒤 1번부터라 `payment_approval_number_key` 에 걸린다 */
    private fun issueApprovalNumber(): String = "M%d%04d".format(System.currentTimeMillis(), random.nextInt(SERIAL_BOUND))

    /** 하이픈과 공백은 사람이 읽으라고 넣은 것이라 걷어낸다. 길이는 ISO/IEC 7812 의 12~19 자리 */
    private fun digitsOf(cardNumber: String): String {
        val digits = cardNumber.filter { it != ' ' && it != '-' }
        require(digits.length in MIN_DIGITS..MAX_DIGITS && digits.all { it.isDigit() }) { "카드번호 형식이 아니다" }
        return digits
    }

    companion object {
        /** 승인·환불 번호 뒤에 붙는 네 자리. `%04d` 와 같이 움직인다 — 넓히면 형식도 같이 넓힌다 */
        private const val SERIAL_BOUND = 10_000

        const val DECLINE_LAST4 = "0000"
        const val SILENT_LAST4 = "0001"
        const val DELAYED_LAST4 = "0002"
        const val DECLINE_REASON = "insufficient_funds"

        private const val MIN_DIGITS = 12
        private const val MAX_DIGITS = 19
    }
}
