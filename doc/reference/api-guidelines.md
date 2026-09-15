# API 설계 규약

우리가 정할 것만 여기 쓰고 나머지는 Zalando RESTful API Guidelines 를 따른다. **벗어난 것은 근거와 함께 아래에 적는다.**

**프론트는 상태 코드가 아니라 오류 `type` 으로 분기한다.** 이 문서에서 제일 중요한 규칙이고, 그래서 `type` 목록이 계약이다.

## 기준에서 벗어난 것

| 무엇 | Zalando | 우리 | 왜 |
|---|---|---|---|
| 열거값 표기 | `UPPER_SNAKE` | **소문자** (`held`, `open`) | DB 가 소문자라 로그·쿼리·응답을 **한 단어로 검색**한다. JSON 을 snake_case 로 한 이유와 같다 |
| 오류 `type` | `https://` URL | `tag:` URI | 없는 도메인을 안 가리킨다. RFC 9457 이 역참조를 요구하지 않는다 |
| 버전 | `/v1/` | 없음 | 쓰지도 않을 체계를 이고 가지 않는다. 깨는 변경이 생기면 그때 `/api/v2/` |

## 이름

| 무엇 | 규칙 | 예 |
|---|---|---|
| JSON 속성 | snake_case | `applied_migrations`, `held_until` |
| URL | 소문자, 복수 명사, 하이픈 | `/api/performances`, `/api/consent-items` |
| 식별자 | 숫자 `id`. 외부 노출 번호는 티켓만(18, `identifier-rules.md`) | `/api/reservations/1042` |
| 열거값 | 소문자 | `"status": "paying"` |

**Kotlin 은 camelCase 고 JSON 은 snake_case 다.** `application.yml` 의 `property-naming-strategy` 가 바꾼다. 화면 코드는 받은 그대로 snake_case 로 읽는다 — 변환 층을 두면 두 이름이 생긴다.

## 자원과 경로

| 경로 | 무엇 | 인증 | 청크 |
|---|---|---|---|
| `GET /api/health` | 앱·DB·마이그레이션 수 | 공개 | 있다 |
| `POST /api/auth/signup` · `login` · `logout` | 가입·로그인·로그아웃 | 공개·공개·세션 | 있다 |
| `GET /api/me` | 내 계정 | 세션 | 있다 |
| `GET /api/consent-items` | 동의 항목 | 공개 | 있다 |
| `GET /api/events` · `GET /api/events/{id}` | 공연 목록·상세(회차 포함) | 공개 | 40 |
| `GET /api/performances/{id}/seats` | 좌석 현황 전체(`D20`) | 공개 | 10 |
| `GET /api/performances/{id}/seats/changes?since=` | 바뀐 좌석(`D20`) | 공개 | 10a |
| `POST /api/queue/{performanceId}` · `GET` · `DELETE` | 대기열 진입·순번·이탈(`D12`) | 세션 | 21·24 |
| **`POST /api/performances/{id}/reservations`** | 좌석 선점 | 세션 + 관문 | 13 |
| `GET /api/reservations/{id}` | 예매 하나 | 세션(본인) | 13 |
| `GET /api/me/reservations` | 내 예매 목록 | 세션 | 44 |
| `POST /api/reservations/{id}/payments` | 결제 시작·결과 | 세션(본인) | 16 |
| `POST /api/reservations/{id}/cancel` | 취소 | 세션(본인) | 17 |
| `GET /api/reservations/{id}/tickets` | 발권된 티켓 | 세션(본인) | 18 |
| `POST /api/organizer/events` | 공연 등록 | 세션(기획사) | 11 |
| `POST /api/organizer/events/{id}/performances` | 회차 등록 | 세션(기획사) | 11 |
| `POST /api/organizer/performances/{id}/open` · `cancel` | 회차 오픈·취소 | 세션(기획사) | 11 (취소 서비스는 17a 가 세웠다) |
| `GET /api/organizer/settlements` | 정산 | 세션(기획사) | 27 |
| `POST /api/admin/accounts/{id}/suspend` · `unsuspend` | 계정 정지·해제 | 세션(관리자) | 5b |
| `DELETE /api/me` | 탈퇴 | 세션 | 5a |

**선점이 회차 아래에 있는 이유**: 대기열 관문(23)이 **회차 id 를 경로에서 읽는다.** 본문에 두면 필터가 JSON 을 파싱해야 하고, 그러면 본문 스트림을 두 번 읽는 문제가 따라온다.
예매가 만들어진 뒤에는 `/api/reservations/{id}` 로 평평하게 간다 — 그때는 예매가 회차를 안다.

**기획사·관리자 경로는 접두로 가른다.** `/api/organizer/…`·`/api/admin/…` 은 역할이 없으면 403 이고, 그 안에서 **자기 것이 아닌 자원**은 404 다(아래 「403 이냐 404 냐」).

## 메서드와 동작

| 메서드 | 언제 |
|---|---|
| `GET` | 조회. 몇 번 해도 같다 |
| `POST` | 생성, 그리고 **CRUD 가 아닌 동작** |
| `DELETE` | 자원을 없앤다 — 대기열 이탈, 탈퇴 |
| `PUT`·`PATCH` | **지금 안 쓴다.** 고칠 자원이 없다. 생기면 `PATCH` 는 `application/merge-patch+json` 이다 |

**상태를 바꾸는 것은 하위 경로에 `POST` 한다** — `…/open`, `…/cancel`, `…/payments`. `PATCH` 로 `status` 를 보내는 방식은 안 쓴다.
전이표(`D3`)를 거치지 않는 상태 변경 경로가 생기기 때문이다. 상태는 **동작의 결과**지 클라이언트가 정하는 값이 아니다.

## 상태 코드

| 코드 | 언제 | 이 저장소의 예 |
|---|---|---|
| 200 | 조회·동작 성공 | 로그인, 취소 |
| 201 | 생성. `Location` 에 새 자원 경로 | 가입, 선점, 결제 |
| 204 | 성공했고 본문이 없다 | 로그아웃, 대기열 이탈 |
| 304 | 재검증 — 안 바뀌었다 | 좌석 현황(`If-None-Match`) |
| 400 | 요청을 못 읽거나 형식이 틀렸다 | 깨진 JSON, `@Size` 위반, **모르는 열거값** |
| 401 | 로그인이 안 됐다. `WWW-Authenticate: Session` 을 붙인다 | |
| 403 | 로그인은 됐는데 권한이 없다. CSRF 실패도 여기 | 관객이 기획사 경로 |
| 404 | 없거나 **남의 것** | 남의 예매 |
| 409 | 형식은 맞는데 **지금 상태가 못 받는다** | 좌석이 이미 잡힘, 허용 안 된 전이, 당일 취소 |
| 410 | 있었는데 사라졌다 | 델타 로그 밖의 `since`, 닫힌 회차의 대기열 |
| 422 | 형식은 맞는데 값이 규칙에 안 맞는다 | 필수 동의 빠짐, 4매 초과 |
| 429 | 지금은 안 되지만 나중엔 된다 | 활성 토큰 없음 — 대기열로 |
| 503 | 우리가 지금 못 한다 | Redis 가 죽어 관문이 닫힘 |

**409 와 422 의 경계**: 「다른 시점이면 통과했나」로 가른다. 좌석은 남이 놓으면 되고 당일은 어제였으면 됐다 → 409. 5매는 언제 해도 안 된다 → 422.

**모르는 열거값은 400 이다.** 요청 필드를 enum 으로 받으므로 역직렬화에서 걸려 검증기 전에 끝난다. 문자열로 받고 정규식을 걸면 422 를 낼 수 있지만 값 목록 사본이 하나 는다 — 사본이 느는 값이 오류 코드 하나보다 크다.

**바깥이 거절한 것은 4xx 가 아니다.** 카드 거절은 요청 처리가 **성공한 결과**라 201 로 내려가고 본문의 `status: "declined"` 가 말한다. 4xx 로 던지면 그 결과를 적은 결제 행이 같이 롤백된다(`D4`).

## 오류 본문 — RFC 9457

```json
{
  "type": "tag:projectticket.example,2026:seat-taken",
  "title": "이미 잡힌 좌석이 있다",
  "status": 409,
  "detail": "3석 중 1석이 이미 잡혔다",
  "instance": "/api/performances/7/reservations",
  "trace_id": "6aa7cecaecbfaf6b5c29c724147a32a0",
  "taken_seat_ids": [9002]
}
```

| 규칙 | 왜 |
|---|---|
| `type` 은 계약이다. 슬러그를 바꾸면 화면이 깨진다 | 프론트가 이것으로 분기한다 |
| `title` 은 다듬어도 된다 | 사람이 읽는 문구다 |
| `detail` 에 개인정보·SQL·스택을 안 담는다 | 그대로 응답에 나간다(`D9`·`D10`) |
| `trace_id` 는 **오류에만** | 성공 응답에 넣으면 모든 응답이 커진다. 되짚어 볼 것은 실패다 |
| 프레임워크가 끊는 오류도 우리 `type` 을 단다 | `ApiExceptionHandler`·`ProblemEntryPoint`·`ProblemAccessDeniedHandler` 셋이 같은 `ProblemFactory` 를 쓴다 |
| 본문 `status` = HTTP 상태 | RFC 9457 §3.1. 표에 없는 상태(406·413)는 가장 가까운 `type` 에 실제 상태를 단다 |

### `type` 목록 — 계약

`ErrorCode` 가 실물이고 이 표는 그 계약이다. **둘이 갈리면 `ErrorCode` 를 고치는 것이 아니라 계약 변경이다** — 화면(39 이후)이 이 슬러그로 분기한다.

| 슬러그 | 상태 | 뜻 | 추가 필드 | 청크 |
|---|---|---|---|---|
| `login-failed` | 401 | 이메일·비밀번호가 안 맞거나 정지된 계정. **셋을 안 가른다** | | 있다 |
| `unauthenticated` | 401 | 로그인 필요 | | 있다 |
| `forbidden` | 403 | 권한 없음·CSRF 실패 | | 있다 |
| `email-taken` | 409 | 가입된 이메일 | | 있다 |
| `unknown-consent-item` · `required-consent-missing` | 422 | 동의 | | 있다 |
| `performance-not-found` | 404 | 회차 없음 | | 있다 |
| `performance-not-openable` | 422 | 좌석 없는 홀, 등급 안 붙은 구역 | `detail` 에 구역 | 있다 |
| `validation-failed` | 400 | Bean Validation | `errors[{field, message}]` | 있다 |
| `malformed-request` · `method-not-allowed` · `unsupported-media-type` · `endpoint-not-found` · `internal` | 400·405·415·404·500 | 프레임워크 | | 있다 |
| **`seat-taken`** | 409 | 고른 좌석 중 이미 잡힌 것이 있다 | `taken_seat_ids` | 13 |
| **`seat-not-in-performance`** | 422 | 좌석 id 가 그 회차 것이 아니다 | `seat_ids` | 13 |
| **`performance-not-open`** | 409 | 회차가 `open` 이 아니다 | `performance_status` | 13 |
| **`over-limit`** | 422 | 한 번에 4석 초과, 또는 회차당 4매 초과 | `limit`, `requested` | 13·15 |
| **`duplicate-hold`** | 409 | 같은 회차에 살아있는 선점이 이미 있다 | `reservation_id` | 15 |
| **`reservation-not-found`** | 404 | 없거나 남의 예매 | | 13 |
| **`invalid-transition`** | 409 | 지금 상태에서 못 하는 것 — 만료된 선점에 결제, 취소된 예매에 결제 | `from`, `action` | 16·17 |
| **`hold-expired`** | 409 | `held_until` 이 지났다. `invalid-transition` 의 특수형 — 화면이 「다시 고르세요」로 가른다 | | 16 |
| **`cancel-window-closed`** | 409 | 관람일 당일이라 취소 불가 | `starts_at` | 17 |
| **`admission-required`** | 429 | 활성 토큰이 없다. 대기열로 | `rank`, `eta_seconds` | 23 |
| **`admission-mismatch`** | 403 | 토큰이 다른 계정·회차 것 | | 23 |
| **`queue-closed`** | 410 | 회차가 닫혀 대기열이 없다 | | 21 |
| **`queue-unavailable`** | 503 | Redis 가 죽어 관문이 닫혔다 | `Retry-After` 헤더 | 23 |
| **`seat-changes-expired`** | 410 | `since` 가 변경 로그 밖이다. 전체를 다시 받아라 | `version` | 10a |
| **`idempotency-in-progress`** | 409 | 같은 키가 처리 중 | | 13 |
| **`idempotency-key-reused`** | 422 | 같은 키인데 본문이 다르다 | | 13 |
| **`organizer-forbidden`** | 403 | 기획사 역할이 아니다 | | 11 |
| **`event-not-found`** | 404 | 없거나 남의 기획사 공연 | | 11 |

**`detail` 은 사람 문장이고 추가 필드가 기계 값이다.** 화면이 `detail` 을 파싱하지 않는다.

### 검증 실패는 어느 필드가 틀렸는지 준다

```json
{ "type": "…:validation-failed", "status": 400,
  "errors": [ { "field": "password", "message": "비밀번호는 15~64자의 ASCII 출력 가능 문자여야 한다" } ] }
```

`field` 는 요청 본문의 이름 그대로 snake_case 다. 요청에 쓴 이름과 오류에 나온 이름이 갈리면 화면이 못 찾는다.

## 403 이냐 404 냐

403 을 주면 **그 자원이 존재한다는 사실이 샌다.** 404 를 주면 클라이언트가 권한 문제인지 오탈자인지 모른다. 자원의 민감도로 가른다.

| 자원 | 남의 것일 때 | 왜 |
|---|---|---|
| 공연·회차·좌석 현황 | 공개다. 권한 실패가 없다 | |
| 예매·결제·티켓 | **404** | 예매 수와 증가 속도가 새면 안 된다. 번호를 훑으면 판매량 지도가 그려진다 |
| 기획사의 공연·회차 | **404** | 다른 기획사의 미공개 공연 존재가 새면 안 된다 |
| 계정(관리자 경로) | **404** | 계정 존재를 굳이 알려주지 않는다 |
| 역할이 없는 경로 접두 | **403** | 존재를 숨길 자원이 없다 — 경로 자체가 공개다 |

로그인이 안 됐으면 자원과 무관하게 **401** 이다.

## 목록 조회

| 파라미터 | 뜻 | 기본 |
|---|---|---|
| `page` | 0 부터 | 0 |
| `size` | 한 페이지. **최대 100** | 20 |
| `sort` | `필드,방향`. 허용 목록 밖이면 400 | 자원마다 |

| 자원 | 정렬 가능 | 기본 |
|---|---|---|
| 공연 목록 | `starts_at`(첫 회차), `created_at` | `starts_at,asc` |
| 내 예매 | 없음 | `created_at,desc` 고정 |
| 정산 | `performance_starts_at` | `performance_starts_at,desc` |

**셋을 컨트롤러가 직접 안 받는다.** `Paging` 하나로 받고 `*Query` 에도 그대로 넘긴다 — 상한 보정이 그 타입의 생성자에 있어서 타입이 있다는 것 자체가 「상한을 거쳤다」는 뜻이다.
`sort` 는 `OrderBy` 타입이 든다. 정렬 필드는 값이 아니라 식별자라 바인딩이 안 되므로 허용 목록으로만 받는다(`D14` 「SQL」).

```json
{ "items": [ … ], "page": 0, "size": 20, "total": 137 }
```

껍데기 이름을 하나로 고정한다. **빈 목록은 `[]`** 다.

## 값의 형식

| 종류 | 형식 | 예 |
|---|---|---|
| 시각 | RFC 3339, **UTC 의 `Z`**(`D7`) | `2026-09-25T10:00:00Z` |
| 날짜 | `YYYY-MM-DD`, KST 업무일 | `2026-09-25` |
| 금액 | 정수, 원 | `154000` |
| 열거값 | 소문자 | `"reserved"` |
| 좌석 상태(현황 응답만) | 한 글자 `A`·`H`·`R` | `D20` — 크기 때문이다 |

**요청의 시각은 오프셋 필수.** 없으면 400. `Z` 를 권한다. `LocalDateTime` 으로 받지 않는다(`D7`).

## null 과 생략

| 상황 | 어떻게 |
|---|---|
| 값이 없다 | `null` 을 내보낸다. 필드를 빼지 않는다 — 빠지면 「없음」과 「아직 안 만든 필드」가 안 갈린다 |
| 빈 목록 | `[]` |
| 모르는 필드가 들어온다 | 무시한다. 새 클라이언트가 옛 서버를 만나도 된다 |
| 모르는 열거값이 들어온다 | 400 — 위 「상태 코드」 |

**응답에서 필드를 빼는 것은 계약 변경이다.** 화면이 그 필드를 읽고 있으면 조용히 `undefined` 가 된다. 빼려면 먼저 화면에서 지우고, 그다음 서버에서 뺀다.

## 헤더

| 헤더 | 방향 | 어디 | 왜 |
|---|---|---|---|
| `Idempotency-Key` | 요청 | 선점·결제 | `D4`. 없으면 400 |
| `X-Admission-Token` | 요청 | 선점 | `D12`. 없으면 429 |
| `X-XSRF-TOKEN` | 요청 | 모든 상태 변경 | CSRF. 쿠키 `XSRF-TOKEN` 값을 그대로 |
| `ETag` / `If-None-Match` | 응답 / 요청 | 좌석 현황 | `D20`. 값은 버전 |
| `Cache-Control` | 응답 | 좌석 현황 `no-cache`, 나머지 `no-store` | 현황은 재검증, 나머지는 캐시 금지 — 세션이 실린 응답이 캐시에 남으면 안 된다 |
| `Location` | 응답 | 201 | 새 자원 |
| `Retry-After` | 응답 | 503 | 초 단위 |
| `WWW-Authenticate: Session` | 응답 | 401 | RFC 9110 §15.5.2 MUST |

## 인증

세션 쿠키(`TICKETSESSION`, httpOnly) + CSRF 쿠키(`XSRF-TOKEN`, JS 가 읽어 헤더로). 401 은 `ProblemEntryPoint`, 403 은 `ProblemAccessDeniedHandler` 가 본문을 만든다.
프론트는 같은 오리진에서 프록시(`next.config.ts` rewrite)로 부른다 — CORS 를 안 연다.

## 이 문서를 고칠 때

**`type` 을 더하면 `ErrorCode` 와 이 표를 같이 고친다.** 39 뒤로는 `ErrorTypeScreenTest` 가 화면의 분기가 이 표 안에 있는지 잰다(`D8`).
경로를 바꾸는 것은 화면·e2e 가 같이 가는 것이라 청크로 세운다.
