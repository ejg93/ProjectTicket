package com.projectticket.ticket

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TicketApplication

// 앱 컨텍스트는 JVM 이 끝날 때까지 산다 — 닫으면 앱이 내려간다
@Suppress("MissingUseCall")
fun main(args: Array<String>) {
    runApplication<TicketApplication>(*args)
}
