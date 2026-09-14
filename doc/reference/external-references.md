# 바깥 참조 문서

> **ProjectShop 에서 이식한 문서다.** 예시·청크 번호(`2g`·`Q36` 등)·쇼핑 도메인(주문·셀러·정산)은 ProjectShop 것이다.
> 티켓 도메인과 Kotlin 에 맞추는 것은 `PLAN.md` 이식 청크 `P` 줄이 한다. 그 청크가 닫히면 이 머리말을 지운다.
> 그때까지는 **원칙은 믿고 예시는 안 믿는다.**

기준 문서(D1~D21)를 쓸 때 근거로 삼는 바깥 자료다.
링크는 2026년 8월 5일에 열어서 확인했다. 회사 문서는 구조가 바뀌므로 안 열리면 검색해서 갱신한다.

## 고르는 게 아닌 것 — 규격

선택지가 없다. 도구가 알아서 따르고, 막힐 때 해당 절만 찾아본다.

| 규격 | 링크 | 우리와의 관계 |
|---|---|---|
| RFC 9110 HTTP Semantics | https://www.rfc-editor.org/rfc/rfc9110.html | 메서드와 상태 코드의 정의 원본 |
| RFC 9457 Problem Details | https://www.rfc-editor.org/rfc/rfc9457.html | 오류 응답 본문 형식. 청크 7b 에서 채택 |
| OpenAPI 3.1 | https://spec.openapis.org/oas/v3.1.0.html | 청크 2a 의 springdoc 이 이 형식으로 뽑는다 |

## D5 API 설계 규약

**기준: Zalando RESTful API Guidelines.** 스타일 가이드는 하나만 고른다.
둘을 섞으면 같은 문제에 서로 다른 답이 들어와 규약이 충돌한다.

- https://opensource.zalando.com/restful-api-guidelines/

확인한 것

- 규칙마다 RFC 2119 의 `MUST`·`SHOULD`·`MAY` 등급이 붙어 있다. 통째로 받아들이지 않고 골라 쓸 수 있다
- OpenAPI 3.1 을 권장한다. 우리 청크 2a 와 맞는다
- 장 구성: REST Basics(메타 정보·보안·데이터 형식·URL·JSON) → HTTP 요청·상태코드·헤더 →
  REST Design(하이퍼미디어·성능·페이징·호환성·폐기) → API Operations → Event Basics·Design
- **Event 장이 따로 있다.** 청크 29(웹훅)·32(아웃박스)의 `D12` 이벤트 카탈로그가 이걸 쓴다

D5 는 우리가 정할 20개 안팎만 본문에 쓰고, 나머지는 "여기 없는 것은 Zalando 를 따른다" 한 줄로 넘긴다.
통째로 베끼면 유지가 안 된다.

## D6 권한 모델 명세

우리 모델이 이미 코드로 있어서 베낄 자리가 아니다. **개념과 점검 목록만 빌린다.**

- XACML 3.0 (OASIS 표준): https://docs.oasis-open.org/xacml/3.0/xacml-3.0-core-spec-en.html

빌린 것

- **결합 알고리즘의 이름.** 우리가 "deny 가 allow 를 이긴다" 고 정한 것이 `deny-overrides` 다
- **판정 결과 4종.** Permit·Deny·NotApplicable·Indeterminate.
  우리 `Decision` 은 앞의 셋을 `boolean` 하나로 뭉쳤고 넷째가 없다. `permission-rules.md` 에 적어 뒀고 청크 7b 에서 정한다

XACML 자체를 도입하지 않는다. XML 정책 언어라 이 규모에 과하다.

참고로만 두는 것

- NIST RBAC (INCITS 359) — "역할" 개념의 공식 정의. 용어가 헷갈릴 때
- Google Zanzibar 논문, OpenFGA, SpiceDB — 관계 기반(ReBAC) 모델.
  우리는 RBAC + 스코프라 **모델이 달라서 섞으면 헷갈린다**

## D15 테스트 전략

**개념 기준: Test Pyramid. 도구 기준: Spring Boot + Testcontainers 공식 문서.**

| 자료 | 링크 |
|---|---|
| The Practical Test Pyramid | https://martinfowler.com/articles/practical-test-pyramid.html |
| Spring Boot Testing (4.1.0) | https://docs.spring.io/spring-boot/reference/testing/index.html |
| Testcontainers for Java | https://java.testcontainers.org/ |
| Spring Boot × Testcontainers | https://docs.spring.io/spring-boot/reference/testing/testcontainers.html |

확인한 것

- Practical Test Pyramid 는 Ham Vocke 가 2018년 2월 26일에 martinfowler.com 에 쓴 글이다.
  Mike Cohn 의 원래 피라미드를 실무용으로 풀었고, **예제가 Java + Spring Boot 다**
- 층은 단위 → 통합 → 계약 → UI → 종단이다. 원칙은 "작고 빠른 단위 테스트를 많이,
  거친 테스트를 조금, 종단 테스트는 아주 적게"
- Spring Boot 공식 문서가 **4.1.x** 를 다룬다. 우리가 쓰는 버전과 같다
- Spring Boot 테스트 장에 **Testcontainers 절이 따로 있다.** 청크 35 가 이걸 쓴다
- Testcontainers 는 **2.0.5** 를 쓴다(`2f-2`). 모듈 좌표가 `org.testcontainers:testcontainers-postgresql` 로
  바뀌었다 — **2.x 부터 접두어가 붙는다.** 문서 사이트에 보이는 숫자를 아티팩트 버전으로 읽으면
  안 된다(한 번 틀렸다). **버전과 좌표는 `repo1.maven.org` 의 `maven-metadata.xml` 과 BOM POM 에서 확인한다** —
  Maven Central 검색 API 는 색인이 늦어서 2.x 를 0건으로 답했다

### Docker Engine 29 와의 비호환

**1.21.4 미만은 Docker 29 에서 안 뜬다.** docker-java 가 API 버전을 1.32 로 잡는데
Docker 29 의 최소 지원이 1.44 라서 `/info` 가 빈 응답과 400 을 준다.

오류 메시지가 `Could not find a valid Docker environment` 라 원인이 안 드러난다.
`DOCKER_HOST` 를 바꾸거나 `DOCKER_API_VERSION` 을 지정해도 안 고쳐지고, 버전을 올려야 풀린다.

- https://github.com/testcontainers/testcontainers-java/issues/11212
- https://github.com/testcontainers/testcontainers-java/issues/11235

Boot 의 BOM 이 Testcontainers 를 관리하지 않으므로 `testcontainers-bom` 을 직접 넣는다.

### 지금 우리 상태와의 차이

피라미드를 기준으로 보면 **지금 테스트가 뒤집혀 있다.**
20개 전부가 Spring 컨텍스트를 띄우고 실제 DB 에 붙는 통합 테스트고, 순수 단위 테스트가 0개다.
그리고 그 DB 가 손으로 띄운 로컬 컨테이너라 Docker Desktop 을 끄면 전부 실패한다.

D15 는 이걸 어디까지 바꿀지 정하는 문서다.

## D3 비즈니스 모델

**기준: Stripe Connect 문서.** 표준이 없는 영역이라 실물을 본다.

- https://docs.stripe.com/connect

우리와 구조가 같은 절

| 절 | 링크 | 무엇을 배우나 |
|---|---|---|
| Build a marketplace | https://docs.stripe.com/connect/marketplace | 고객에게 받아서 판매자에게 나눠 주는 구조 |
| Create a charge | https://docs.stripe.com/connect/charges | 플랫폼과 판매자 사이에 결제를 어떻게 쪼개나 |
| Account balances | https://docs.stripe.com/connect/account-balances | 잔액이 언제 잡히고 언제 풀리나 |
| Pay out to connected accounts | https://docs.stripe.com/connect/payouts-connected-accounts | 지급 주기와 외부 계좌 |
| Platform pricing tool | https://docs.stripe.com/connect/platform-pricing-tools | 플랫폼 수수료를 어디에 매기나 |

**Charges 와 Account balances 가 핵심이다.** 청크 17~21(정산)에서 만날 어려운 경우가 여기 있다.
정산 후에 환불이 들어오면 이미 준 돈을 어떻게 회수하나, 분쟁 중인 금액을 어떻게 잡아 두나.

결제를 흉내로 만들어도 이 구조는 그대로 배운다.

### 요율의 현실적 범위

D3 의 기본 요율 10% 는 아래를 보고 가운데를 잡은 값이다. 2026년 8월에 확인했다.

| 마켓플레이스 | 판매수수료 |
|---|---|
| 쿠팡 | 4~10.8% (카테고리별) |
| 11번가 | 6~13% |
| G마켓 | 4~15% |

- https://www.tosspayments.com/blog/articles/semo-31 — 오픈마켓 수수료 비교
- https://blog.fassto.ai/contents/ecommerssue/2025_open_market_commission — 2025년 기준 정리

전부 **카테고리별로 요율이 다르다.** 우리는 카테고리 체계가 없어서 상품 단위 예외로 대신한다.

### 안 쓰기로 한 것

- Business Model Canvas — 고객 세그먼트·채널 같은 칸을 채우는 도구다.
  우리에게 필요한 것은 **돈의 흐름과 확정 시점**이라 해상도가 안 맞는다

## D14 보안 기준

**기준: OWASP Top 10.** ASVS 5.0.0 도 후보였지만 요구사항이 수백 개라 이 규모에 무겁다.
통째로 대면 "안 지킨 항목" 목록만 길어지고, 그 목록이 길면 아무도 안 본다.

| 자료 | 링크 | 확인한 것 |
|---|---|---|
| OWASP Top 10 **2025** | https://owasp.org/Top10/2025/ | 웹 애플리케이션의 위험 열 가지. 항목이 적어 전부 훑을 수 있다. **2026-01 최종.** `D14` 를 `D14-1` 이 이 판으로 다시 매핑했다 |
| OWASP Cheat Sheet Series | https://cheatsheetseries.owasp.org/ | 주제별 실무 지침. Top 10 이 "무엇이 위험한가" 라면 이쪽이 "어떻게 막나" 다 |
| **NIST SP 800-63B Rev 4** | https://pages.nist.gov/800-63-4/sp800-63b.html | 비밀번호 규칙의 근거. **문장 단위로 대조했다**(점검 E) — 단독 인증수단이면 **최소 15자 `SHALL`**, 블록리스트 대조 `SHALL`, 조합 강제 `SHALL NOT`, 최대 64자·인쇄 ASCII+공백·유니코드는 `SHOULD` |
| Spring Security 레퍼런스 | https://docs.spring.io/spring-security/reference/index.html | **7.1.0**. CSRF 와 Session Management 절이 따로 있다 |

Spring Security 문서에서 청크가 직접 쓰는 절

- CSRF: https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html — 청크 5b
- Session Management: https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html — 청크 5·5c
- Method Security: 청크 74(판정 엔진 재구현)가 `AuthorizationManager` 를 볼 때
- MockMvc 설정: https://docs.spring.io/spring-security/reference/servlet/test/mockmvc/setup.html — 청크 35a

이슈 하나를 근거로 쓴다.

- spring-security#12813 — https://github.com/spring-projects/spring-security/issues/12813
  `with(csrf())` 가 `CsrfTokenRepository` 를 세션 기반으로 갈아치워 쿠키 발급 검증이 불가능해진다.
  `status: invalid` 로 닫혔다 — 의도된 동작이라 우회가 아니라 층을 옮겨야 한다(`stack.md`)

D14 는 Top 10 열 항목을 훑고 **각각이 이 프로젝트의 어디에 걸리는지** 를 적는다.
D2 법 요건표와 같은 구조다. 해당 없는 항목은 왜 해당 없는지를 적어야 빠뜨린 것과 구분된다.

ASVS 는 버린 것이 아니라 미룬 것이다. 특정 주제를 깊게 봐야 할 때 그 장만 꺼내 본다.
https://owasp.org/www-project-application-security-verification-standard/ (현재 5.0.0, 2025-05-30)

## D9 식별자 규약

| 자료 | 링크 | 확인한 것 |
|---|---|---|
| RFC 9562 UUID | https://www.rfc-editor.org/rfc/rfc9562.html | RFC 4122 를 대체. **UUIDv7 이 새로 들어왔다** |

UUIDv7 이 우리에게 중요한 이유가 있다.
UUIDv4 는 완전 난수라 정렬이 안 되고, 기본키로 쓰면 인덱스가 매번 랜덤한 자리에 삽입돼서 성능이 나빠진다.
UUIDv7 은 앞부분이 밀리초 단위 시각이라 시간순으로 정렬되고 인덱스 지역성이 좋다.

지금 우리 기본키는 `bigint generated always as identity` 다.
D9 에서 정할 것은 **바깥에 노출하는 번호**다. 주문번호를 `1, 2, 3` 으로 내보내면
남의 주문 수와 증가 속도가 드러난다. 내부 ID 와 노출 번호를 가를지, 가른다면 무엇을 쓸지가 D9 의 주제다.

## D8 금액·통화 규약

| 자료 | 링크 | 확인한 것 |
|---|---|---|
| Money 패턴 (Fowler) | https://martinfowler.com/eaaCatalog/money.html | 통화 혼동과 반올림 오차를 타입으로 막는 패턴 |

Money 패턴이 지적하는 두 문제가 우리에게 그대로 온다.

- **통화 혼동** — 지금은 원화뿐이라 안 겪는다. 다중 통화를 안 하기로 했으므로 D8 에 그렇게 적어 둔다
- **반올림 오차** — 겪는다. 수수료가 `주문금액 × 요율` 이라 소수가 나오고,
  주문 항목마다 반올림한 합과 주문 전체를 반올림한 값이 1원씩 어긋난다. 청크 18(수수료 계산)에서 터진다

ISO 4217 이 통화 코드 표준(`KRW`, `USD`)이고 `java.util.Currency` 가 이걸 따른다.
지금은 안 쓰지만 금액 컬럼에 통화를 안 붙이기로 한 결정은 D8 에 남긴다.

## D11 동시성·트랜잭션 규약

| 자료 | 링크 | 확인한 것 |
|---|---|---|
| PostgreSQL 17 격리 수준 | https://www.postgresql.org/docs/17/transaction-iso.html | 기본이 **Read Committed**. Repeatable Read 와 Serializable 을 쓰려면 명시해야 한다 |
| Stripe 멱등 요청 | https://docs.stripe.com/api/idempotent_requests | 멱등키 설계의 실물 |

Postgres 문서에서 확인한 것

- 표준 4단계 중 Read Uncommitted 는 Read Committed 처럼 동작한다. 실제로는 3단계다
- **기본값 Read Committed 에서는 팬텀 리드가 난다.** 재고 차감(청크 10)이 여기 걸린다
- Repeatable Read 와 Serializable 은 직렬화 실패를 던지므로 **재시도 코드가 있어야 한다**

Stripe 멱등키에서 확인한 것 — 우리 청크 12(모의 결제)가 그대로 따라할 만하다

- 클라이언트가 키를 만든다. V4 UUID 를 권장한다
- **첫 요청의 상태 코드와 본문을 저장**하고, 같은 키로 오면 그것을 그대로 돌려준다. 500 이어도 그렇다
- 키는 24시간 뒤 지운다
- 같은 키에 다른 파라미터가 오면 오류를 낸다. 실수로 재사용하는 것을 막는다
- POST 만 받는다. GET·DELETE 는 정의상 멱등이라 키가 무의미하다

## D12 이벤트 카탈로그

| 자료 | 링크 | 확인한 것 |
|---|---|---|
| CloudEvents | https://cloudevents.io/ | CNCF **Graduated** 프로젝트(2024-01-25). 스펙 1.0.2 (2022-02-05) |
| Zalando Event 장 | https://opensource.zalando.com/restful-api-guidelines/ | D5 기준 문서 안에 이벤트 설계 장이 따로 있다 |

CloudEvents 는 이벤트 봉투(envelope)의 표준이다. `id`·`source`·`type`·`specversion` 같은 공통 속성을 정한다.
청크 29(웹훅)에서 셀러에게 내보내는 이벤트 모양이 **대외 계약**이 되므로,
우리 마음대로 만든 형식보다 표준을 쓰면 받는 쪽이 기존 라이브러리를 쓸 수 있다.

**도입 여부는 보류다.** 청크 29·32 를 잡을 때 실제 필요를 보고 정한다.
지금 정하면 이벤트가 하나도 없는 상태에서 봉투 형식을 고르는 셈이라 판단 근거가 없다.

## D16 관측 규약

**기준: Micrometer Tracing + W3C Trace Context 헤더 형식.** OpenTelemetry 는 지금 안 붙인다.

| 자료 | 링크 | 확인한 것 |
|---|---|---|
| W3C Trace Context | https://www.w3.org/TR/trace-context/ | **W3C Recommendation** (2021-11-23). `traceparent`·`tracestate` 헤더 |
| Spring Boot 관측 | https://docs.spring.io/spring-boot/reference/actuator/tracing.html | Micrometer Tracing 연동 |
| OpenTelemetry | https://opentelemetry.io/docs/ | 신호 3종 — 트레이스·메트릭·로그. 청크 62·63 에서 다시 본다 |

청크 2b 는 요청마다 추적 ID 를 붙이고 모든 로그 줄에 찍는다.
로그가 뒤엉켰을 때 요청 하나를 처음부터 끝까지 따라가려는 것이다.

**헤더 형식만 `traceparent` 를 따른다.** 자체 헤더를 만들면 청크 62·63 에서 관측 도구를 붙일 때 갈아엎어야 한다.
지금 OpenTelemetry 를 붙이면 수집기·저장소·화면 컨테이너가 늘어나는데 볼 화면이 없다.

감사 로그(4b)와 헷갈리지 말 것. 감사 로그는 "누가 무슨 권한으로 뭘 했나" 를 DB 에 남기고,
이쪽은 "이 요청이 어디서 느려졌나" 를 로그로 남긴다. 목적도 보관 위치도 다르다.

## D17 파일·미디어 규약

| 자료 | 링크 | 확인한 것 |
|---|---|---|
| OWASP File Upload Cheat Sheet | https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html | 검증과 저장의 권고 |

확인한 권고 중 청크 26~28 에 그대로 걸리는 것

- 확장자는 **허용 목록**으로 검사한다. 차단 목록은 우회된다
- `Content-Type` 은 사용자가 보낸 값이라 믿을 수 없다. 파일 시그니처를 본다
- 파일명은 UUID 같은 무작위 문자열로 새로 짓는다. 사용자가 준 이름을 그대로 쓰지 않는다
- **저장 위치 우선순위**: 별도 호스트 > 웹루트 밖 > 웹루트 안(쓰기 전용 + 접근 제어)
- 크기 제한과 인증·인가를 같이 건다

청크 28(파일 접근 판정)이 다루는 "URL 만 알면 보인다" 문제가 저장 위치 결정과 묶여 있다.

## D21 성능 목표

| 자료 | 링크 | 확인한 것 |
|---|---|---|
| Google SRE Book — SLO 장 | https://sre.google/sre-book/service-level-objectives/ | SLI·SLO·SLA 의 구분 |

- **SLI** — 측정값. 요청 지연, 오류율, 가용성
- **SLO** — 그 측정값의 목표. "99% 가용", "지연 100ms 이하"
- **SLA** — 목표를 못 지켰을 때의 **결과가 붙은 계약.** 결과가 없으면 SLO 다

우리는 로컬 전용이라 SLA 는 없다. D21 은 SLI 와 SLO 만 정한다.
목표가 없으면 청크 42(성능 튜닝)와 70(부하 테스트)이 끝을 못 정한다.

## D4 도메인 모델 · D1 용어집

| 자료 | 링크 | 무엇 |
|---|---|---|
| DDD Reference (Eric Evans) | https://www.domainlanguage.com/wp-content/uploads/2016/05/DDD_Reference_2015-03.pdf | 무료 공개 PDF. 유비쿼터스 언어·바운디드 컨텍스트·애그리거트의 정의 요약 |

책 전체가 아니라 **정의 요약본**이라 짧다. D1 이 쓰는 것은 유비쿼터스 언어 하나다 —
같은 것을 코드·문서·대화에서 같은 말로 부른다는 원칙.
지금 `seller` 를 셀러·판매자·입점사로 섞어 쓰고 있어서 D1 이 이걸 고정한다.

애그리거트 개념은 D4 가 쓴다. 무엇이 같이 태어나고 같이 죽는지를 정하면
`on delete cascade` 를 어디에 걸지가 따라 나온다.

## D5 API 규약 (완료)

- OpenAPI Specification 3.1.1: https://spec.openapis.org/oas/v3.1.1.html — 스펙 형식의 정본
- springdoc-openapi: https://springdoc.org — 코드에서 스펙을 뽑는 도구(`2a`).
  **관례(4순위)라 기본값을 그대로 안 받는다** — 경로를 `/v3/api-docs` 에서 `/api/docs` 로 옮긴 근거는
  `application.yml` 에 적었다

## D2 법·정책 (완료)

- 국가법령정보센터: https://law.go.kr — 법령 원문의 정본.
  **평문 URL 은 제목만 준다 — `print=print` 판을 받아야 조문이 온다**(`W2` 가 남긴 사실, 2026-09-10 에 적었다)
- 상세는 `commerce-compliance.md`

### 조문 대조 (2026-08-08)

전자상거래법 열 조문의 제목과 내용을 확인했다. 확인한 것은 `commerce-compliance.md` 「검증 상태」에 있다.

| 자료 | 링크 | 무엇을 확인했나 |
|---|---|---|
| 전자상거래법 전문 (CaseNote) | https://casenote.kr/법령/전자상거래_등에서의_소비자보호에_관한_법률 | 조문 제목 10개, 제12조 신고 면제 단서, **제17조제2항 제한 사유 6호** |
| 통신판매업 신고면제 기준 고시 | https://www.law.go.kr/행정규칙/통신판매업신고면제기준에대한고시 | 면제 기준이 시행령이 아니라 **공정위 고시**라는 것 |
| 공정거래위원회 | https://www.ftc.go.kr/www/contents.do?key=153 | 전자상거래법 주요 내용 |

**국가법령정보센터 본문에 직접 닿지 못했다.** 페이지가 스크립트로 본문을 그려서
가져오면 "해당 법령이 존재하지 않습니다" 만 나온다. 위 셋으로 교차 대조했다.

**이 대조에서 두 가지가 고쳐졌다.**

- 제17조제2항에 **5호(용역·디지털콘텐츠 제공 개시)** 가 빠져 있었다 → `V13` 의 `check` 에 `digital_content` 추가
- 신고 면제 근거를 시행령으로 적었는데 **공정위 고시**다. 기준은 직전연도 거래횟수 50회 미만 또는 간이과세자

## 아직 참조를 안 정한 문서

표준이나 널리 쓰이는 실물이 없거나, 우리가 정하는 비중이 커서 참조가 덜 필요한 것들이다.
해당 청크를 잡을 때 필요하면 그때 찾는다.

| 문서 | 왜 미뤘나 |
|---|---|
| D7 상태머신 카탈로그 | UML 상태기계 표기를 빌리는 정도다. 전이표는 우리 도메인이라 베낄 것이 없다 |
| D10 시각·영업일 규약 | ISO 8601 과 IANA tz 데이터베이스가 전부다. 영업일 계산은 국내 공휴일이라 표준이 없다 |
| D13 데이터 수명 정책 | D2 의 R6·R9 가 근거다. 새 참조가 필요 없다 |
| D18 알림 정책 | D2 의 R14(정보통신망법)가 근거다 |
| D19 배치·스케줄 카탈로그 | cron 표기와 Spring Scheduling 문서면 된다 |
| D20 화면·문구 규약 | WCAG(접근성)는 범위 밖이다. 시각 디자인은 청크 `13-0` 의 스킬이 맡고, 무엇을 감추고 보이나는 우리 권한 모델에 붙은 판단이라 베낄 것이 없다 |

## D22 명명 규칙

| 자료 | 링크 | 무엇을 가져오나 |
|---|---|---|
| SQL Style Guide (Simon Holywell) | https://www.sqlstyle.guide/ | 컬럼·테이블 명명, 표준 접미사. CC BY-SA 4.0 |
| Google Java Style Guide | https://google.github.io/styleguide/javaguide.html | Java 식별자 대소문자 규칙 |

**SQL 가이드는 `id` 를 기본키 이름으로 쓰지 말라고 명시한다.** 우리는 그 권고를 새 테이블부터 따르고,
기존 13개는 안 바꾼다 — 이유는 `naming-rules.md` 맨 위에 있다.

`SQL Antipatterns`(Bill Karwin)도 같은 방향(`bug_id`)을 말한다. 책이라 링크로 대조할 수 없어서
기준으로 안 잡았지만, 두 자료가 같은 결론이라는 점은 이 결정의 근거가 된다.

## 유효성 확인 이력

**점검 E** 가 링크를 실제로 열어 대조한다. 표준은 개정되고, 개정되면 **우리 규약이 2순위 근거를 잃는다.**

| 확인일 | 무엇을 봤나 |
|---|---|
| 2026-08-05 | 링크를 처음 열어 확인하고 이 문서를 만들었다 |
| **2026-08-14** | 점검 E. **핵심 근거 여섯을 원문으로 대조**했다(아래) |

### 2026-08-14 에 확인한 것

| 자료 | 결과 |
|---|---|
| RFC 9457 | **현행.** Standards Track 이고 폐지 안 됐다. RFC 7807 을 폐지한 쪽이 이것이다 |
| **OWASP Top 10** | **개정됐다 — 2025 판.** 2025-11 발표, 2026-01 최종. `D14` 가 2021 에 매핑돼 있다 |
| **NIST SP 800-63B** | **개정됐다 — Revision 4.** 비밀번호 `SHALL` 둘을 우리가 못 지킨다 |
| W3C Trace Context | Level 2 가 **후보 권고 초안**이다. `traceparent` 형식은 그대로고 하위호환이라 `D16` 은 안 바뀐다 |
| KWCAG 2.2 | **현행.** `KS X OT0003:2022` 다. `D20` 그대로 |
| Flyway | Apache-2.0 그대로 |

**나머지 링크는 이번에 안 열었다.** Stripe·Zalando·Spring 처럼 회사가 갱신하는 문서와
`D12`·`D17`·`D21` 처럼 아직 청크가 안 온 것이 그렇다. 다음 점검 E 에서 본다.

## 라이선스

`D23` 「라이선스」가 **의존성과 인용 자료 둘 다** 보라고 한다. 그 기록 자리가 여기다.

### 인용 자료

| 자료 | 라이선스 | 우리가 한 것 |
|---|---|---|
| SQL Style Guide (Simon Holywell) | **CC BY-SA 4.0** | 규칙을 골라 쓰고 벗어난 것을 따로 적었다. **`2l-2` 가 그 절을 CC BY-SA 4.0 으로 표시했다**(2026-09-06) — 파생물인지 인용인지는 여전히 판단 안 했고, **표시로 어느 쪽이든 성립한다** |
| OWASP (Top 10 · Cheat Sheet) | CC BY-SA 4.0 | 항목 이름을 청크에 매핑했다 |
| NIST SP 800-63B | 미국 정부 저작물 — 저작권 없음 | 요건을 근거로 인용 |
| RFC 9457 · W3C Trace Context | IETF Trust / W3C 문서 라이선스 | 형식을 따랐다 |
| Zalando RESTful API Guidelines | MIT | 규칙을 따르고 벗어난 둘을 명시했다 |
| `design-taste-frontend` 스킬 (Leonxlnx/taste-skill) | **MIT** | 사본이 `.agents/skills/design-taste-frontend/` 에 있다. **고지를 그 옆 `LICENSE` 에 뒀다**(`2l-1`) — MIT 는 사본에 저작권 고지가 있어야 성립한다 |

### 의존성

`build.gradle.kts` 에 줄을 추가할 때 본다. **GPL 계열은 0건**이다.

| 의존성 | 라이선스 |
|---|---|
| Spring Boot · Spring Security · Spring Framework | Apache-2.0 |
| Micrometer · Micrometer Tracing (Brave) | Apache-2.0 |
| Caffeine | Apache-2.0 |
| Flyway (Community) | Apache-2.0 (2026-08-14 확인) |
| Jackson | Apache-2.0 |
| PostgreSQL JDBC Driver | BSD-2-Clause |
| Testcontainers | MIT |
| Next.js · React · Tailwind CSS | MIT |
| react-markdown · remark-gfm (unified 계열) | MIT (2026-08-17 확인) |

`react-markdown` 은 **정책 문서를 그리려고 넣었다**(`13a-2`). 본문이 DB 에 마크다운으로 들어가서
문법을 우리가 통제하지 않고, 처리방침이 목록과 표를 쓴다 — 표는 CommonMark 가 아니라 GFM 이라
`remark-gfm` 이 같이 온다. **직접 쓴 렌더러는 안 다루는 문법을 오류 없이 원문 글자로 내보낸다.**

**판단한 적 없는 것과 판단해서 괜찮은 것은 다르다**(`D23`). 위 표가 그 판단을 기록한 것이다.
새 의존성을 넣으면 이 표에 줄을 더한다.

## 이 문서를 고칠 때

링크가 죽거나 문서 구조가 바뀌면 여기를 고친다.
새 기준 문서를 정할 때 그 근거 자료를 여기 추가한다.

회사가 내는 문서(Zalando·Stripe·Spring)는 갱신되므로 **버전을 적어 두지 않는다.**
대신 무엇을 확인했는지를 적어서, 나중에 내용이 달라졌을 때 알아볼 수 있게 한다.
