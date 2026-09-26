package com.projectticket.ticket.payment

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 취소 수수료 구간표(`42-1a`, `D6`). 결제 **전**에 보여 준다 — 약관 제3조가 「결제 전에 보여 준다」고 약속했다.
 * 로그인 없이 본다(`SecurityConfig.PUBLIC_GET_PATHS`) — 예매 하나에 묶이지 않은 공통 표라서다. 예매 하나의 액수는 [RefundPreviewController].
 */
@RestController
class RefundTierController(private val quote: RefundQuote) {

    @GetMapping("/api/refund-tiers")
    fun tiers(): RefundQuote.Tiers = quote.tiers()
}
