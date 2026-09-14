# 명명 규칙

DB 와 Kotlin 의 이름을 정한다. 기준은 `sqlstyle.guide` 와 Kotlin 공식 코딩 컨벤션이고,
**벗어난 자리는 아래에 근거와 함께 적는다** — 가이드를 통째로 받거나 버리지 않는다(`D14` 축 1).

## SQL

| 대상 | 규칙 | 예 |
|---|---|---|
| 테이블 | **단수**, snake_case | `account`, `performance_seat` |
| 기본키 | `<테이블>_id` | `account_id`, `audit_log_id` |
| 외래키 | 가리키는 쪽의 기본키 이름 그대로 | `account_consent.account_id` |
| 시각 | `_at` 으로 끝난다. 타입은 `timestamptz` | `created_at`, `held_until` 은 예외 |
| 불리언 | `is_` 로 시작한다 | `is_required` |
| 제약 | `<테이블>_<컬럼>_<종류>` | `account_role_check`, `account_email_key` |
| 인덱스 | `<테이블>_<무엇>_idx` | `audit_log_actor_idx` |
| 함수·트리거 | 하는 일을 말한다 | `set_updated_at`, `audit_log_append_only` |

### 기준에서 벗어난 것

| 무엇 | 가이드 | 우리 | 왜 |
|---|---|---|---|
| 테이블 이름 | 복수형 | **단수** | 행 하나를 가리키는 말이 테이블 이름과 같아야 코드에서 읽힌다 |
| 기본키 | `id` | **`<테이블>_id`** | 조인에서 `a.id = b.a_id` 가 아니라 `a.account_id = b.account_id` 가 된다. 같은 뜻의 컬럼이 같은 이름이다 |

두 번째는 `sqlstyle.guide` 가 「`id` 를 기본 식별자로 쓰지 말라」고 한 것을 따른 것이고, 첫 번째는 같은 가이드를 버린 것이다.

### `held_until` 같은 이름

`_at` 은 「일어난 시각」이고 `_until` 은 「거기까지 유효한 시각」이다. **뜻이 다르면 접미사도 다르다** —
`held_at` 으로 적으면 읽는 쪽이 선점한 시각인지 만료 시각인지 매번 물어야 한다.

## Kotlin

| 대상 | 규칙 |
|---|---|
| 클래스·인터페이스 | PascalCase |
| 함수·속성 | camelCase |
| 상수(`const val`) | UPPER_SNAKE_CASE |
| 열거값 | UPPER_SNAKE_CASE |
| 패키지 | 소문자, 자원 이름 그대로 |

### DB 컬럼과 Kotlin 속성

**컬럼 snake_case, 속성 camelCase 로 그대로 옮긴다.** `account_id` → `accountId`.

**JSON 은 다시 snake_case 로 나간다**(`application.yml` 의 `property-naming-strategy`).
DB 컬럼명과 JSON 필드명이 같아져서 로그·쿼리·응답을 같은 단어로 검색할 수 있다.

### 불리언의 `is_` 는 Kotlin 에서 뗀다

`is_required` → `required`. 붙여 두면 접근자가 `isRequired` 라 `is` 가 두 번 붙는다.

**그래서 조회 SQL 이 별칭을 준다** — `select is_required as required`. `query(T::class.java)` 가 컬럼명으로
생성자 인자를 맞추기 때문이다. 별칭을 빠뜨리면 그 필드만 조용히 기본값이 된다.

### 축약하지 않는다

| 안 쓴다 | 쓴다 |
|---|---|
| `perf`, `res`, `cnt` | `performance`, `reservation`, `count` |
| `usr`, `acc` | `account` |
| `tmp`, `val2` | 무엇인지 말하는 이름 |

**널리 통하는 것만 예외다** — `id`, `url`, `ip`, `sql`, `api`.

### 입구 이름은 그 입구가 하는 일을 말한다

| 접미사 | 무엇 |
|---|---|
| `*Controller` | HTTP 입구. 웹 애너테이션은 여기만 붙는다(`ArchitectureTest`) |
| `*Service` | 쓰기 쪽. 트랜잭션 경계가 여기 |
| `*Query` | 읽기 쪽. 트랜잭션을 안 연다 |
| `*Filter` | 서블릿·보안 필터 |
| `*Test` | 테스트. 그 밖의 접미사를 안 쓴다 |

## 도메인 용어

**한글과 영문의 짝은 `glossary.md` 가 든다.** 여기서 다시 정하지 않는다 —
두 벌이 되면 한쪽이 낡는데 낡은 것을 아무도 안 잡는다.

코드에는 영문만 쓴다. 화면 문구의 한글은 `screen-rules.md`(`D17`)가 정한다.

## 이 문서를 고칠 때

**이미 쓴 이름을 바꾸는 것은 마이그레이션이다.** 컬럼 이름을 고치면 그 컬럼을 읽는 SQL·타입·응답이 같이 간다.
바꿀 값이 있으면 그 범위를 먼저 세고, 청크 하나로 안 떨어지면 분할표에 행을 세운다.
