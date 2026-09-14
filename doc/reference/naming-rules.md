# 명명 규칙

> **ProjectShop 에서 이식한 문서다.** 예시·청크 번호(`2g`·`Q36` 등)·쇼핑 도메인(주문·셀러·정산)은 ProjectShop 것이다.
> 티켓 도메인과 Kotlin 에 맞추는 것은 `PLAN.md` 이식 청크 `P` 줄이 한다. 그 청크가 닫히면 이 머리말을 지운다.
> 그때까지는 **원칙은 믿고 예시는 안 믿는다.**

무엇을 어떤 이름으로 부르나. DB 컬럼과 Java 식별자를 한 장에서 정한다.

JSON 속성 이름은 여기서 안 다룬다. `api-guidelines.md`(D5)가 Zalando 를 따라 `snake_case` 로 정했다.
**다만 그 결정 때문에 DB 컬럼명이 API 로 그대로 샌다** — 아래 「이 규칙은 API 로 샌다」를 본다.

## 기준

| 대상 | 기준 자료 |
|---|---|
| SQL | [SQL Style Guide](https://www.sqlstyle.guide/) (Simon Holywell, CC BY-SA 4.0) |
| Java | [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) |

둘 다 살아 있고 널리 인용되며 링크로 대조할 수 있다.
`SQL Antipatterns`(Bill Karwin)도 같은 방향을 말하지만 책이라 인용 대조가 어려워서 기준으로 안 잡았다.

> **아래 「기준에서 벗어난 것」과 「SQL」 절은 SQL Style Guide(Simon Holywell)에서 골라 온
> 규칙이고 인용이라 이 두 절에 한해 [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) 을 따른다**(`2l-2`).
> 저장소의 나머지는 Apache-2.0 이다(루트 `LICENSE`). 경계는 「Java」 절 앞까지다.
>
> **파생물인지 인용인지 판단하지 않았다.** 어느 쪽이든 이 표시로 성립해서 판단할 필요가 없다 —
> 저장소가 공개라(`2l`) 틀린 쪽으로 판단하면 라이선스 위반이 된다.

## 기준에서 벗어난 것

먼저 적는다. 벗어난 곳을 뒤에 숨기면 다음 사람이 기준만 읽고 어긋난 코드를 짠다.

### 1. 테이블은 단수형이다

가이드는 복수형(`employees`)을 권한다. 우리는 `app_user`, `role`, `seller` 처럼 단수를 쓴다.

**코드의 타입 이름과 1:1로 붙이려는 것이다.** `app_user` ↔ `AppUser`, `consent_item` ↔ `ConsentItem`.
복수형이면 매핑에 "복수를 단수로 되돌리는" 규칙이 하나 더 붙고, 불규칙 복수에서 어긋난다.

### 2. 시각 컬럼은 `_at` 이다

가이드의 표준 접미사에는 `_date` 만 있다. 우리는 `created_at`, `deleted_at`, `acted_at` 을 쓴다.

`_date` 는 날짜형에 맞는 이름인데 **우리는 전부 `timestamptz` 다**(`D10` 이 UTC 저장으로 정했다).
날짜가 아니라 시각이므로 `_at` 이 값을 더 정확히 말한다.

### 3. 마이그레이션을 고쳐서 기존 테이블도 맞췄다

가이드는 **"`id` 를 기본 식별자 이름으로 쓰지 말라"** 고 명시한다. 이 규칙도 그쪽을 따른다.

처음에는 기존 테이블을 안 바꾸기로 했다가 **바꾸는 쪽으로 뒤집었다.**
상품 축이 테이블을 다섯 개 더 붙이기 직전이라, 그 전에 맞추는 것이 제일 쌌다.

**이미 적용된 마이그레이션 파일을 직접 고쳤다.** 보통은 하면 안 되는 일이다 —
Flyway 체크섬이 깨지고, 남이 이미 그 마이그레이션을 돌렸으면 되돌릴 방법이 없다.
**로컬 전용이고 배포한 적이 없어서** 가능했다. 청크 3e 가 그 작업이다.

**배포가 한 번이라도 나가면 이 방법은 못 쓴다.** 그때는 `alter table ... rename column` 을
새 마이그레이션으로 추가한다.

## SQL

### 공통

| 규칙 | 값 |
|---|---|
| 문자 | 소문자, 숫자, 밑줄만 |
| 길이 | 30자 이하 |
| 시작·끝 | 글자로 시작하고 밑줄로 끝내지 않는다 |
| 예약어 | 쓰지 않는다 — `user` 를 못 써서 `app_user` 가 됐다 |

### 테이블

단수형. `tbl_` 같은 접두사를 안 붙인다. 테이블과 같은 이름의 컬럼을 두지 않는다.

### 기본키

**`<테이블>_id`.** `product` 의 기본키는 `product_id` 다.

**예약어를 피하려고 접두사가 붙은 테이블은 엔티티 이름을 쓴다.**
`app_user` 의 기본키는 `app_user_id` 가 아니라 **`user_id`** 다.
`app_` 는 `user` 가 SQL 예약어라서 붙은 것이고 이 테이블이 담는 것은 사용자다.
외래키가 이미 `user_id`·`actor_user_id` 이므로 이렇게 해야 이름이 실제로 같아진다.

`shop_order` 도 같다 — 기본키가 `order_id` 다. `order` 가 예약어라 `shop_` 이 붙었을 뿐
이 표가 담는 것은 주문이다.

**1:1 로 붙는 확장 표는 본체의 기본키를 그대로 기본키로 쓴다.** `order_shipping` 의 기본키는
`order_shipping_id` 가 아니라 **`order_id`** 다. 주문 하나에 배송지가 하나뿐이라
대리키를 얹으면 아무도 안 쓰는 번호가 생기고 "주문당 하나" 를 유니크 제약으로 따로 적게 된다.
바로 아래 「값 자체가 식별자인 표」와 같은 논리다.

이렇게 하면 외래키가 같은 이름이 되어 조인에서 이름이 안 바뀐다.

```sql
select * from product p join sku s using (product_id)
```

`using` 을 쓸 수 있고, `select *` 로 여럿을 조인해도 `id` 가 여러 개 나오는 혼란이 없다.
`Map<String, Object>` 로 받을 때 키가 덮이는 사고도 안 난다.

**연결 테이블에서 특히 값을 한다.** `user_role` 은 지금 `user_id, role_id, id, seller_id` 인데
그 `id` 가 무엇의 id 인지 이름만으로 안 드러난다. 새 규칙이면 `user_role_id` 다.

**값 자체가 식별자인 표는 그 값을 기본키로 둔다.** `holiday` 의 기본키는 `holiday_date` 다.
대리키를 얹으면 아무도 안 쓰는 번호가 생기고, "같은 날이 두 번 들어가면 안 된다" 를
기본키가 아니라 유니크 제약으로 따로 적게 된다. 달력·코드표가 이 꼴이다.

### 외래키

**참조하는 테이블의 기본키 이름을 그대로 쓴다.** `sku.product_id` 는 `product.product_id` 를 가리킨다.

같은 테이블을 두 번 참조해서 이름이 겹치면 역할을 앞에 붙인다.
`consent_item.depends_on_id` 가 그 예다 — 같은 테이블을 가리키므로 `consent_item_id` 를 못 쓴다.

### 접미사

| 접미사 | 뜻 |
|---|---|
| `_id` | 식별자 |
| `_at` | 시각 (`timestamptz`) |
| `_status` | 상태 값 |
| `_type` | 종류 |
| `_name` | 이름 |
| `_code` | 코드에서 참조하는 안정된 키 |
| `_count` | 개수 |
| `_total` | 합계 |
| `_bp` | 만분율 정수 (1000 = 10.00%) |
| `_reason` | 고정된 사유 값 |
| `_number` | **우리가 발급해서 바깥이 부르는 번호** |
| `_no` | 그 밖의 번호 — 안에서 세우는 순번, 남이 발급한 번호 |

`_num` 은 안 쓴다. 개수인지 번호인지 안 갈린다 — 개수는 `_count` 다.

**가르는 기준은 「누가 발급했나」다**(`Q42`). `order_number`·`settlement_number` 는 **우리가 만들어
내보내는 것**이라 `identifier-rules.md` 가 형식까지 정하고 **한 번 내보내면 못 바꾼다.**
`sort_no` 는 안에서 줄을 세우는 수이고, `business_reg_no`·`mail_order_no` 는 **국가가 발급한 번호**다 —
둘 다 우리가 형식을 정하지 않으므로 그 표의 대상이 아니다.

**`SchemaNamingTest` 가 막는다** — `_no` 로 끝나는 컬럼은 허용 목록에 적힌 것뿐이고,
항목마다 **왜 우리 노출 번호가 아닌지**를 적는다. 새로 생기면 그 자리에서 묻게 된다.

### 불리언은 `is_` 로 시작한다

`is_required`, `is_system`, `is_org_role`.

**기존에 어긋난 것이 둘 있다.** `user_consent.granted` 는 접두사가 없다.
안 바꾼다 — 그 컬럼은 `current_consent` 뷰와 응답에 그대로 나가고, 이름만 고치면 API 가 바뀐다.

`return_request.restock` 도 같다(`V63`). 요청 필드 이름이 그대로 컬럼이 됐고
`ShipmentController` 를 지나 API 에 나간다 — 고치면 부르는 쪽이 같이 바뀐다.

**`SchemaNamingTest` 가 막는다**(`Q31`). 도는 스키마에 물어서 재고, **이름으로 적는 예외는
위 둘과 `holiday.holiday_date` 셋뿐이다.** 예외마다 이 문서의 절 이름이 붙어 있어서
절이 없으면 목록에 못 들어간다.

**예외를 이름으로 안 적고 구조로 가르는 자리가 있다.** 1:1 확장 표 여덟은
「기본키 컬럼이 외래키이기도 하다」로 알아본다 — 이름을 여덟 개 적으면 아홉 번째에 목록이 낡는다.

## Java

Google Java Style Guide 를 따른다. 아래는 그 위에 우리가 더 정한 것뿐이다.

### DB 컬럼과 Java 필드

컬럼은 `snake_case`, 필드는 `lowerCamelCase`. **이름이 바뀌는 자리는 여기 하나뿐이다.**

```
DB      created_at      password_hash    is_required
Java    createdAt       passwordHash     required
JSON    created_at      password_hash    is_required
```

불리언의 `is_` 는 Java 에서 뗀다. 게터가 `isRequired()` 라 접두사가 두 번 붙는다.

### 이름을 짓는 자리

| 대상 | 규칙 | 예 |
|---|---|---|
| 판정·조회 서비스 | 명사 | `PermissionEvaluator`, `AuditLogQuery` |
| 판정이 참고하는 표 | `<무엇>Policy` | `StatusPolicy`, `OrderStatusPolicy` |
| 요청·응답 record | `<동작>Request` / `<동작>Response` | `SignupRequest`, `LoginResponse` |
| 서비스 입력 record | `Command` (서비스 안에 중첩) | `SignupService.Command` |
| 조회 조건 | `Criteria` | `AuditLogQuery.Criteria` |
| 목록 응답 | `Page` (항목·페이지·크기·전체) | `AuditLogQuery.Page` |
| DB 행을 그대로 담은 것 | `Row` (private) | `AccountService.Row` |
| 도메인 값 | 그 값의 이름 그대로 | `Decision`, `Target`, `Rule`, `Account` |
| 테스트 | `<대상>Test` | `PermissionEvaluatorTest` |
| 테스트 바탕 | `<무엇>TestBase` | `PostgresTestBase`, `HttpTestBase` |

**`Request`·`Response` 는 HTTP 경계에서만 쓴다.** 서비스 안쪽까지 그 이름이 들어가면
서비스가 웹을 아는 모양이 된다(`coding-rules.md` 의 「예외」와 같은 이유다).

### 식별자 필드는 어디까지 컬럼명을 따르나

**DB 행을 담은 것만 컬럼명을 그대로 쓴다.** `AuditLogQuery.Row` 의 기본키는 `auditLogId` 다 —
`id` 로 두면 JSON 도 `id` 로 나가서 컬럼명(`audit_log_id`)과 갈린다(`D5`).

**도메인 객체는 타입 이름이 대상을 말하므로 `id` 로 둔다.** `ShopUserDetailsService.ShopUser.id()` 가 그것이다.
DB 에서 읽어 만들지만 응답으로 안 나가고, 타입에 `User` 가 이미 들어 있어서 `userId` 는 같은 말을 두 번 한다.

가르는 기준은 **응답으로 나가느냐**다. 나가면 컬럼명, 안 나가면 타입 이름에 기댄다.

### 축약하지 않는다

`cnt`, `usr`, `perm` 을 안 쓴다. 길이를 아껴서 얻는 것보다 읽을 때 잃는 것이 크다.
널리 쓰이는 것(`id`, `url`, `http`)은 예외다.

## 이 규칙은 API 로 샌다

`D5` 가 **DB 컬럼명과 JSON 필드명을 같게** 정했다. 로그·쿼리·응답을 같은 단어로 검색하려는 것이다.

그 대가로 **컬럼 이름을 바꾸면 API 응답이 바뀐다.** 이름을 고칠 때 그 자원을 쓰는 화면이
있는지 먼저 본다. 위에서 `granted` 와 기존 `id` 를 안 바꾸기로 한 이유가 이것이다.

## 이 문서를 고칠 때

새 테이블을 만들면서 여기 없는 접미사를 쓰게 되면 **그 접미사를 여기 먼저 추가한다.**
표에 없는 이름이 스키마에 먼저 들어가면 다음 사람이 그것을 관례로 본다.
