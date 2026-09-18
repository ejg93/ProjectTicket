package com.projectticket.ticket.auth

import jakarta.servlet.Filter

/**
 * 인가가 끝난 뒤에 도는 필터.
 *
 * **의존을 뒤집으려고 둔다.** 보안 설정이 업무 패키지(`queue` 등)를 직접 알면 `auth → queue → auth` 순환이다
 * (`ArchitectureTest.noPackageCycles` 가 실제로 잡았다) — 업무 쪽이 이 인터페이스를 구현하고 [SecurityConfig] 는
 * 구현이 무엇인지 모른 채 체인에 끼운다.
 *
 * 인가 **뒤**인 이유는 로그인 안 한 요청이 여기 오면 안 되기 때문이다. 대기열 관문이 그 예다 —
 * 줄을 서라는 말은 로그인한 사람에게만 뜻이 있다(401 이 먼저다).
 */
interface SecuredApiFilter : Filter
