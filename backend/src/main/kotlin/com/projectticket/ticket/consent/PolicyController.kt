package com.projectticket.ticket.consent

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 처리방침 화면(`39-3b`)이 읽는 자리(`39-3a`). 공개다 — 제30조 제2항이 「쉽게 확인할 수 있도록」 공개하라고 정해서
 * 로그인 전에도, 발(footer)에서도 닿아야 한다(`SecurityConfig.PUBLIC_GET_PATHS`).
 */
@RestController
@RequestMapping("/api/policies")
class PolicyController(private val policyQuery: PolicyQuery) {

    @GetMapping("/{code}")
    fun policy(@PathVariable code: String): PolicyQuery.PolicyDocument = policyQuery.current(code)
}
