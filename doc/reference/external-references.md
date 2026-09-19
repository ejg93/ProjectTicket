# 바깥 참조 문서

기준 문서(`PLAN.md` 「기준 문서」의 D 줄)를 쓸 때 근거로 삼는 바깥 자료다. 링크는 ProjectShop 에서 2026-08 에 열어 확인한 것을 가져왔고, 이 저장소에서 다시 연 것은 아래 「확인 이력」에 적는다.
회사 문서는 구조가 바뀌므로 안 열리면 검색해서 갱신한다.

## 고르는 게 아닌 것 — 규격

| 규격 | 링크 | 우리와의 관계 |
|---|---|---|
| RFC 9110 HTTP Semantics | https://www.rfc-editor.org/rfc/rfc9110.html | 메서드·상태 코드·`ETag`/`If-None-Match`(§13.1.2)·401 챌린지(§15.5.2)의 정본 |
| RFC 9457 Problem Details | https://www.rfc-editor.org/rfc/rfc9457.html | 오류 응답 본문. `ErrorCode`·`ProblemFactory` |
| RFC 4151 `tag:` URI | https://www.rfc-editor.org/rfc/rfc4151.html | 오류 `type` 이 `tag:projectticket.example,2026:…` 인 근거 — 없는 도메인을 안 가리킨다 |
| RFC 9562 UUID | https://www.rfc-editor.org/rfc/rfc9562.html | 멱등키(UUIDv4)의 형식. UUIDv7 은 안 쓴다(`identifier-rules.md`) |
| ISO/IEC 7812 | — | 카드번호 12~19자리. `MockPaymentGateway`·`PayRequest` 의 길이 |
| RFC 5321 | https://www.rfc-editor.org/rfc/rfc5321.html | 이메일 254 옥텟 — `account_email_length_check` 가 `octet_length` 로 재는 이유 |

## D5 API 규약

**기준: Zalando RESTful API Guidelines.** 스타일 가이드는 하나만 고른다 — 둘을 섞으면 같은 문제에 다른 답이 들어온다.

- https://opensource.zalando.com/restful-api-guidelines/

규칙마다 RFC 2119 등급이 붙어 골라 쓸 수 있다. `D5` 는 우리가 정할 것만 본문에 쓰고 나머지는 Zalando 를 따른다. Event 장이 따로 있어 `D11`(이벤트 카탈로그, 25)이 쓴다.

## D4 동시성·트랜잭션 규약

| 자료 | 링크 | 확인한 것 |
|---|---|---|
| PostgreSQL 17 격리 수준 | https://www.postgresql.org/docs/17/transaction-iso.html | 기본 Read Committed. **§13.2.1 — `UPDATE` 가 고른 행이 다른 트랜잭션에 갱신 중이면 기다렸다가 `WHERE` 를 다시 평가한다.** 조건부 UPDATE 가 「승자 하나」를 보장하는 근거고, ADR 0006 이 그것을 수치로 뒷받침했다 |
| Stripe 멱등 요청 | https://docs.stripe.com/api/idempotent_requests | 클라이언트가 키를 만든다(UUIDv4 권장), 24시간, 같은 키에 다른 본문은 오류. **우리는 실패를 저장하지 않는다** — Stripe 와 다른 자리고 `D4` 에 적었다 |
| Transactional Outbox | https://microservices.io/patterns/data/transactional-outbox.html | 25 가 쓴다 |

## D3 상태기계

UML 상태기계 표기를 빌리는 정도다. 전이표는 우리 도메인이라 베낄 것이 없다. `paying` 상태의 근거는 ADR 0004(스윕과 승인의 경합).

## D8 테스트 전략

| 자료 | 링크 |
|---|---|
| The Practical Test Pyramid | https://martinfowler.com/articles/practical-test-pyramid.html |
| Spring Boot Testing | https://docs.spring.io/spring-boot/reference/testing/index.html |
| Spring Boot × Testcontainers | https://docs.spring.io/spring-boot/reference/testing/testcontainers.html |
| Testcontainers for Java | https://java.testcontainers.org/ |

### Docker Engine 29 와의 비호환

**Testcontainers 1.21.4 미만은 Docker 29 에서 안 뜬다.** docker-java 가 API 1.32 를 잡는데 Docker 29 의 최소 지원이 1.44 라 `/info` 가 400 을 준다. 오류가 `Could not find a valid Docker environment` 라 원인이 안 드러난다.

- https://github.com/testcontainers/testcontainers-java/issues/11212
- https://github.com/testcontainers/testcontainers-java/issues/11235

Boot BOM 이 Testcontainers 를 관리하지 않으므로 `testcontainers-bom` 을 직접 넣는다(2.0.5).

## D9 보안 기준

**기준: OWASP Top 10 2025.** ASVS 는 요구사항이 수백 개라 이 규모에 무겁다 — 주제를 깊게 볼 때만 그 장을 꺼낸다.

| 자료 | 링크 | 확인한 것 |
|---|---|---|
| OWASP Top 10 2025 | https://owasp.org/Top10/2025/ | 2026-01 최종. `D9` 의 대응표가 이 판이다 |
| OWASP Cheat Sheet Series | https://cheatsheetseries.owasp.org/ | 「어떻게 막나」 |
| NIST SP 800-63B Rev 4 | https://pages.nist.gov/800-63-4/sp800-63b.html | 비밀번호 — 최소 15자 `SHALL`, 블록리스트 `SHALL`, 조합 강제 `SHALL NOT`, 최대 64자·인쇄 ASCII `SHOULD`. `Password` 애너테이션의 근거 |
| Spring Security 레퍼런스 | https://docs.spring.io/spring-security/reference/index.html | CSRF · Session Management · MockMvc 절 |
| spring-security#12813 | https://github.com/spring-projects/spring-security/issues/12813 | `with(csrf())` 가 저장소를 세션 기반으로 갈아치운다. `invalid` 로 닫힘 — 우회가 아니라 층을 옮긴다(`stack.md`) |

## D10 관측 규약

| 자료 | 링크 | 확인한 것 |
|---|---|---|
| W3C Trace Context | https://www.w3.org/TR/trace-context/ | `traceparent` 형식. Level 2 초안이 있지만 하위호환이라 `D10` 은 안 바뀐다 |
| Spring Boot 관측 | https://docs.spring.io/spring-boot/reference/actuator/tracing.html | Micrometer Tracing + Brave. 자동설정·브리지 둘 다 필요(`stack.md`) |

OpenTelemetry 는 30 이 붙인다. 지금 붙이면 볼 화면 없이 컨테이너만 는다.

## D7 시각 규약

ISO 8601 과 IANA tz 가 전부다. 한국은 서머타임이 없어 `Asia/Seoul` 이 늘 `+09:00` 이다 — 하루가 늘 24시간이라 경계 계산에 예외가 없다.

## D6 환불 규약

| 자료 | 링크 | 확인한 것 |
|---|---|---|
| 전자상거래법 제17조 | https://www.law.go.kr/%EB%B2%95%EB%A0%B9/%EC%A0%84%EC%9E%90%EC%83%81%EA%B1%B0%EB%9E%98%EB%93%B1%EC%97%90%EC%84%9C%EC%9D%98%EC%86%8C%EB%B9%84%EC%9E%90%EB%B3%B4%ED%98%B8%EC%97%90%EA%B4%80%ED%95%9C%EB%B2%95%EB%A5%A0/%EC%A0%9C17%EC%A1%B0 | 제2항 — 「시간이 지나 다시 판매하기 곤란할 정도로 가치가 현저히 감소한 경우」의 청약철회 제한, 사전 고지 요건. 고지 자리는 약관 제3조(`V3`)와 취소 화면(44) |
| Money 패턴 (Fowler) | https://martinfowler.com/eaaCatalog/money.html | 반올림은 **한 번**, 합계에 — `RefundPolicy.fee` 가 예매 합계에 한 번 half-up 하는 이유. 통화는 원화 하나라 컬럼에 안 붙인다 |

**확인하지 않은 것**: 공정거래위원회 소비자분쟁해결기준 공연업 항목의 구간과 우리 표(ADR 0003)가 같은지. 법률 검토가 필요해지면 그때 본다(`D6` 「법과의 관계」).

## D21 정산 규약

**기준: Stripe Connect.** 표준이 없는 영역이라 실물을 본다 — 고객에게 받아 판매자(기획사)에게 나눠 주는 구조, 잔액이 언제 잡히고 풀리나, 정산 뒤 환불의 회수.

| 절 | 링크 |
|---|---|
| Build a marketplace | https://docs.stripe.com/connect/marketplace |
| Create a charge | https://docs.stripe.com/connect/charges |
| Account balances | https://docs.stripe.com/connect/account-balances |
| Payouts | https://docs.stripe.com/connect/payouts-connected-accounts |

요율 10% 는 국내 오픈마켓 판매수수료(4~15%, 카테고리별)의 가운데를 잡은 시작값이다(ADR 0003). `settlement_policy` 행으로 두므로 값을 바꾸는 것이 배포가 아니다.

## D11 이벤트 카탈로그 (25)

| 자료 | 링크 | 확인한 것 |
|---|---|---|
| CloudEvents | https://cloudevents.io/ | CNCF Graduated. 봉투(`id`·`source`·`type`·`specversion`)의 표준. **25 가 안 쓰기로 닫았다** — 소비자가 우리뿐이라 표준 봉투가 사 줄 것이 없다(`event-catalog.md`) |
| Zalando Event 장 | https://opensource.zalando.com/restful-api-guidelines/ | 위 D5 문서 안 |

봉투는 우리 것이다(`event_id`·`aggregate`·`type`·`payload`). 표준을 다시 볼 자리는 **바깥에서 우리 사건을 받는 소비자가 생길 때**다.

## D13 성능 목표 (31 뒤)

| 자료 | 링크 | 확인한 것 |
|---|---|---|
| Google SRE Book — SLO 장 | https://sre.google/sre-book/service-level-objectives/ | SLI(측정값)·SLO(목표)·SLA(결과가 붙은 계약). 로컬 전용이라 SLA 는 없다 |

측정값 없이 안 쓴다(`CLAUDE.md` 「문서가 코드보다 먼저다 — 조건이 찼을 때만」). 19 의 락 비교(`doc/notes/lock-comparison.md`)가 첫 숫자다.

## D15 명명 규칙

| 자료 | 링크 | 무엇을 가져오나 |
|---|---|---|
| SQL Style Guide (Simon Holywell) | https://www.sqlstyle.guide/ | 컬럼·테이블 명명. `id` 를 기본키 이름으로 쓰지 말라는 권고를 따르고, 복수형 테이블은 버렸다(`naming-rules.md`). CC BY-SA 4.0 |
| Kotlin Coding Conventions | https://kotlinlang.org/docs/coding-conventions.html | 식별자 대소문자·`data class`·널 안전 |

## D2 도메인 모델 · D1 용어집

| 자료 | 링크 | 무엇 |
|---|---|---|
| DDD Reference (Eric Evans) | https://www.domainlanguage.com/wp-content/uploads/2016/05/DDD_Reference_2015-03.pdf | 유비쿼터스 언어·애그리거트의 정의 요약. 무엇이 같이 태어나고 죽는지가 `on delete cascade` 자리를 정한다 |

## D12 대기열 (21)

표준이 없다. Redis 명령 문서(ZSET·`TIME`)와 ADR 0004 의 관문 범위가 근거다. 21 이 초안을 쓰며 필요하면 여기 더한다.

## 참조를 안 정한 것

| 문서 | 왜 |
|---|---|
| D14 코딩 규약 | 우리가 정하는 비중이 크다. 계층·강제 지점 축은 이 저장소의 결정이다 |
| D16·D17 화면 | WCAG 2.2(https://www.w3.org/TR/WCAG22/)만 — `P9` 가 그 문서를 다시 쓸 때 더한다 |
| D18 품질 게이트 | detekt·CodeQL 문서 — `P10` |
| D20 좌석 읽기 모델 | ADR 0005 가 근거다. 표준이 없다 |

## 라이선스

의존성과 인용 자료 둘 다 본다. **GPL 계열은 0건**이다.

### 의존성

| 의존성 | 라이선스 |
|---|---|
| Kotlin · kotlin-reflect · jackson-module-kotlin | Apache-2.0 |
| Spring Boot · Spring Security · Spring Framework | Apache-2.0 |
| Micrometer · Micrometer Tracing (Brave) | Apache-2.0 |
| Flyway (Community) · flyway-database-postgresql | Apache-2.0 |
| Jackson 3 | Apache-2.0 |
| PostgreSQL JDBC Driver | BSD-2-Clause |
| Testcontainers | MIT |
| ArchUnit | Apache-2.0 |
| JUnit · AssertJ | EPL-2.0 · Apache-2.0 |

`build.gradle.kts` 에 줄을 추가할 때 이 표에 줄을 더한다. 화면 의존성(Next.js·React — MIT)은 39 가 더한다.

### 인용 자료

| 자료 | 라이선스 | 우리가 한 것 |
|---|---|---|
| SQL Style Guide | CC BY-SA 4.0 | 규칙을 골라 쓰고 벗어난 것을 `naming-rules.md` 에 적었다 |
| OWASP (Top 10 · Cheat Sheet) | CC BY-SA 4.0 | 항목을 청크에 매핑했다 |
| NIST SP 800-63B | 미국 정부 저작물 | 요건을 근거로 인용 |
| RFC · W3C 문서 | IETF Trust / W3C 문서 라이선스 | 형식을 따랐다 |
| Zalando RESTful API Guidelines | MIT | 규칙을 따르고 벗어난 것을 `D5` 에 명시했다 |
| ProjectShop 의 문서·코드 | 같은 저자 | ADR 0002 가 무엇을 가져왔는지 든다 |

## 확인 이력

| 확인일 | 무엇을 봤나 |
|---|---|
| 2026-08-05 · 08-14 | ProjectShop 에서 링크를 열어 확인했다(RFC 9457 현행, OWASP 2025 판, NIST Rev 4, W3C Level 2 초안) |
| 2026-09-15 | `P11` — 티켓 도메인에 맞춰 다시 썼다. **링크를 다시 열지는 않았다.** 다음 `/inspection` 이 연다 |
| 2026-09-19 | `I5`(점검 5차) — **요건이 코드에 박혔나**를 봤다(가로 · 표준). 링크는 다시 안 열었다 — 그것은 「바깥 근거」 줄이고 `I5-1` 이 선다 |

## 이 문서를 고칠 때

새 기준 문서를 정할 때 그 근거 자료를 여기 더한다. 회사 문서(Zalando·Stripe·Spring)는 갱신되므로 버전을 적지 않고 **무엇을 확인했는지**를 적는다.
