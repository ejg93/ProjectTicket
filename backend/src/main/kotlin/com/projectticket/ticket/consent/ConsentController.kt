package com.projectticket.ticket.consent

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 가입 화면이 무엇에 동의를 받아야 하는지 묻는 자리. 항목이 데이터라 화면이 코드로 목록을 들면 안 된다 */
@RestController
@RequestMapping("/api/consent-items")
class ConsentController(private val consentService: ConsentService) {

    @GetMapping
    fun items(): List<ConsentService.ConsentItem> = consentService.currentItems()

    /** 약관·처리방침 화면(`39-1`)이 본문을 읽는다. 공개다 — 가입 전에도, 발(footer)에서도 닿아야 한다(약관규제법 제3조) */
    @GetMapping("/{code}")
    fun item(@PathVariable code: String): ConsentService.ConsentItemDetail = consentService.currentItem(code)
}
