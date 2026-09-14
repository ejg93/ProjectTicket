package com.projectticket.ticket.account

import com.projectticket.ticket.auth.TicketUserDetailsService.TicketUser
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 내 계정. 세션의 principal 이 가리키는 행을 읽는다 — 이메일·이름은 세션이 아니라 DB 가 진실이다(변경이 세션에 안 반영된다) */
@RestController
@RequestMapping("/api/me")
class MeController(private val jdbc: JdbcClient) {

    @GetMapping
    fun me(@AuthenticationPrincipal user: TicketUser): MeResponse =
        jdbc.sql("select account_id, email, display_name, role from account where account_id = :id")
            .param("id", user.id)
            .query(MeResponse::class.java)
            .single()

    data class MeResponse(val accountId: Long, val email: String, val displayName: String, val role: String)
}
