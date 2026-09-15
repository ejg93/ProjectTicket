package com.projectticket.ticket

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 스케줄러를 켜는 스위치. 이 클래스가 없으면 `@Scheduled` 가 붙어 있어도 한 번도 안 돈다.
 *
 * 기동 클래스에 안 붙였다 — 따로 두면 프로필로 끌 자리가 생긴다. 테스트 컨텍스트에서도 돌지만 스윕은 전부 조건부 UPDATE 라
 * 테스트가 만든 살아있는 선점(+5분)은 안 건드리고, 롤백 테스트의 행은 다른 커넥션이라 보이지도 않는다.
 *
 * 인스턴스가 여럿이 되면 같은 스케줄러가 대수만큼 돈다. 결과는 같지만(전부 멱등) 같은 행을 두 번 훑는다 — 33 이 Redis 락으로 하나만 돌린다(`D4`).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig
