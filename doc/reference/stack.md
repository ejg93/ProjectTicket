# 스택과 버전

무엇을 어느 버전으로 쓰는지 적고, **기억으로 쓰면 틀리는 자리**를 표시한다.

## 이 문서는 API 레퍼런스가 아니다

메서드 이름이나 설정 키를 여기 옮겨 적지 않는다. 옮겨 적는 사람이 그 버전을 정확히 모르면 틀린 것이 문서로 굳고, 라이브러리가 올라가면 낡는데 낡은 줄 모르고 본다.
API 가 필요하면 아래 공식 문서를 연다. **여기 적는 것은 「어디를 봐야 하나」와 「무엇을 조심하나」뿐이다.**

## 버전

| 대상 | 버전 | 어디에 박혀 있나 |
|---|---|---|
| Spring Boot | 4.1.1 | `backend/build.gradle.kts` |
| Kotlin | 2.3.21 | 같은 파일의 플러그인. **Boot BOM 이 관리하는 값과 맞춘다** — 플러그인은 BOM 밖이라 직접 적는다 |
| Java | 25 | 같은 파일의 toolchain. CI 도 25 |
| Gradle | 9.7.1 | `backend/gradle/wrapper/gradle-wrapper.properties` |
| PostgreSQL | 17-alpine | `docker-compose.yml`. 테스트 컨테이너도 같은 이미지다(`PostgresTestBase`) |
| Redis | 7-alpine | `docker-compose.yml`, `PostgresTestBase`. 세션이 여기 산다(`20a`). 대기열·좌석 캐시는 `21`·`D20` |
| detekt | 2.0.0-alpha.6 | `backend/build.gradle.kts`, 설정은 `backend/config/detekt/detekt.yml`. **알파인 이유는 아래 「기억으로 쓰면 틀리는 자리」** |
| Kafka | 4.3.1 | `docker-compose.yml`(`apache/kafka`, KRaft). 사건 브로커(28, ADR 0007). 로컬은 **9094** — 9092 는 ProjectShop 것 |
| nginx | 1.27-alpine | `docker-compose.yml`, `docker/nginx/nginx.conf`. 인스턴스 셋 앞의 문(33) |
| Prometheus | v3.1.0 | `docker-compose.yml`. 수집기 — 앱은 `micrometer-registry-prometheus` 로 `/actuator/prometheus` 를 연다(30) |
| Grafana | 11.5.0 | `docker-compose.yml`. 데이터 소스·대시보드는 `docker/grafana/provisioning/` 이 심는다 |
| Testcontainers | 2.0.5 | `build.gradle.kts` 의 BOM. **Boot BOM 이 관리하지 않는다** |
| ArchUnit | 1.5.0 | `build.gradle.kts`. **`archunit-junit6`** — 이 저장소가 JUnit 6 이다 |
| Jackson | 3.x | 안 적는다. Boot BOM 이 준다. 패키지가 `tools.jackson` |
| Spring Security | 7.x | 안 적는다. Boot BOM 이 준다 |
| Node | 22 | `.github/workflows/ci.yml`. 화면이 쓴다 |
| Next | 16.3.5 | `frontend/package.json`. App Router. **판을 올리면 `frontend-rules.md` 의 캐시 절부터 다시 본다** — 기본값이 판마다 갈렸다 |
| React | 19.3.0 | `frontend/package.json`. `useFormStatus` 가 여기서 온다 |
| 패키지 매니저 | npm | 잠금 파일이 `frontend/package-lock.json`. CI 는 `npm ci` 라 잠금과 어긋나면 선다 |

**버전을 물으면 이 표가 아니라 위 파일들을 본다.** 표가 낡을 수 있다 — `StackVersionConsistencyTest` 가 이 표와 파일을 대조해서 낡으면 빨개진다.

## 공식 문서

| 대상 | 링크 |
|---|---|
| Spring Boot | https://docs.spring.io/spring-boot/index.html |
| Spring Framework | https://docs.spring.io/spring-framework/reference/index.html |
| Spring Security | https://docs.spring.io/spring-security/reference/index.html |
| Kotlin | https://kotlinlang.org/docs/home.html |
| PostgreSQL 17 | https://www.postgresql.org/docs/17/index.html |
| Testcontainers | https://java.testcontainers.org/ |

설계 근거로 삼은 자료(Zalando·OWASP·Stripe 등)는 `external-references.md` 에 따로 있다. 이 표는 구현할 때 여는 것이다.

## 기억으로 쓰면 틀리는 자리

### 테스트가 전부 실패하면 Docker 부터 본다

Docker Desktop 이 꺼져 있으면 **테스트가 하나도 안 통과한다.** `PostgresTestBase` 가 Testcontainers 로 Postgres 를 직접 띄우기 때문이다(`D8`).

읽히는 오류가 코드 문제처럼 생겼다는 것이 함정이다 — 맨 앞에 나오는 것은 `IllegalStateException at DefaultCacheAwareContextLoaderDelegate` 고,
Spring 컨텍스트가 안 뜬 이야기라 **방금 고친 코드를 의심하게 된다.** 진짜 원인은 스택 맨 아래 `Could not find a valid Docker environment` 한 줄이다.

```
docker info --format "{{.ServerVersion}}"
```

이것이 실패하면 코드를 보지 말고 Docker Desktop 을 띄운다. 기동에 시간이 걸려서 바로 다시 돌리면 같은 오류가 난다.

### `bootRun` 을 죽여도 8080 은 안 풀린다

`bootRun` 은 앱을 **자식 JVM** 으로 띄운다. Gradle 쪽 프로세스를 죽이면 그 자식은 남고, 포트를 쥔 채라 다음 기동이 `Port 8080 was already in use` 로 끝난다.
**그때 `curl` 은 200 을 준다** — 낡은 인스턴스가 답하기 때문이다. 마이그레이션이나 응답 형식을 고친 뒤라면 **옛 코드로 검증하게 되는 자리다.**

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

### `JAVA_HOME` 이 JDK 11 을 가리킨다

이 환경의 문제다. backend 명령 앞에 `JAVA_HOME="C:/Program Files/Java/jdk-25"` 를 붙인다(`CLAUDE.md` 「검증」). 안 붙이면 훅이 막는다.

### Testcontainers 2.x 는 좌표와 클래스가 같이 움직였다

BOM 만 올리면 **`Could not find org.testcontainers:postgresql:`** 로 죽는다. 2.x 부터 모듈에 `testcontainers-` 접두어가 붙었다.

| 1.x | 2.x |
|---|---|
| `org.testcontainers:postgresql` | `org.testcontainers:testcontainers-postgresql` |
| `org.testcontainers:junit-jupiter` | `org.testcontainers:testcontainers-junit-jupiter` |
| `org.testcontainers.containers.PostgreSQLContainer` | `org.testcontainers.postgresql.PostgreSQLContainer` |

**새 클래스는 제네릭이 아니다.** 옛 클래스는 남아 있고 deprecated 경고만 뜬다.
**버전과 좌표는 검색 API 말고 저장소에서 본다.** Maven Central 검색 API 는 색인이 늦어서 2.x 를 0건으로 답한 적이 있다 — `repo1.maven.org` 의 `maven-metadata.xml` 과 BOM POM 이 실물이다.
**1.21.4 미만은 Docker 29 에서 안 뜨고** 오류 문구에 원인이 안 드러난다(`external-references.md`).

### Boot 4 는 스타터 이름과 자동설정 패키지가 3.x 와 다르다

| Boot 4 | 3.x |
|---|---|
| `spring-boot-starter-webmvc` | `spring-boot-starter-web` |
| `spring-boot-starter-flyway` | `flyway-core` 직접 |
| `spring-boot-starter-webmvc-test`·`-security-test`·`-flyway-test`·`-jdbc-test`… | `spring-boot-starter-test` 하나 |
| `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` | `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc` |

규칙은 **기술 이름이 앞으로 나오고 `autoconfigure` 가 뒤로 간다.** 의존성을 추가할 때 기억으로 쓰지 말고 `build.gradle.kts` 에 이미 있는 형태를 따른다.
추적도 둘로 쪼개졌다 — `spring-boot-micrometer-tracing-brave` 와 `micrometer-tracing-bridge-brave` 를 **둘 다** 넣어야 하고, 하나가 빠지면 `Tracer.NOOP` 이 떠서 추적 ID 가 조용히 안 찍힌다.

### Jackson 3 이라 패키지가 `tools.jackson` 이다

| Boot 4 (Jackson 3) | 3.x (Jackson 2) |
|---|---|
| `tools.jackson.databind.ObjectMapper` | `com.fasterxml.jackson.databind.ObjectMapper` |
| `tools.jackson.databind.node.StringNode` | `TextNode` |
| `JsonNode.asString()` | `asText()` |

`com.fasterxml.jackson.core:jackson-annotations` 는 아직 2.x 라 의존성 트리에 두 이름이 같이 보인다. 애너테이션만 옛 이름이다.
Kotlin `data class` 를 읽고 쓰려면 `tools.jackson.module:jackson-module-kotlin` 이 있어야 한다 — 없으면 기본 생성자가 없다고 죽는다.
Jackson 의 기본 들여쓰기가 OS 줄바꿈을 쓴다 — 스냅샷 파일은 `\n` 으로 맞춘다(`Snapshot`).

### 422 의 이름이 바뀌었다

RFC 9110 이 422 를 「Unprocessable Content」로 고쳤다. `HttpStatus.UNPROCESSABLE_CONTENT`·`isUnprocessableContent()` 가 지금 이름이고 옛 이름은 deprecated 다.

### Kotlin 의 검증 애너테이션은 `@field:` 로 붙인다

`data class` 생성자 파라미터에 `@Size` 를 그냥 붙이면 파라미터에 붙어 Bean Validation 이 못 본다. 이 저장소는 `@field:NotBlank` 처럼 대상을 명시하고,
`build.gradle.kts` 의 `-Xannotation-default-target=param-property` 가 명시 없는 자리를 받친다. `ScreenLengthTest`(39)가 `@Size(max)` 를 리플렉션으로 읽을 때 이 자리를 본다.

### `@Transactional` 은 자기 호출에 안 먹는다

프록시가 호출을 가로채는 방식이라 **같은 객체 안에서 부른 것은 프록시를 안 거친다.** 전파 설정이 통째로 무시되고 `REQUIRES_NEW` 가 `REQUIRED` 처럼 돈다 —
**고쳤다고 믿는 채로 원래 결함이 남는 모양이라 증상으로는 안 갈린다.**

그래서 트랜잭션 경계가 다른 것은 **빈을 가른다**: `AuditLog`/`AuditLogWriter`(별도 트랜잭션), `PaymentService`/`PaymentTransitionService`(PG 호출은 밖, 전이는 안), `RefundService`/`RefundTransitionService`.

### `@Transactional` 테스트 안에서는 서비스의 롤백이 안 보인다

서비스의 `@Transactional` 이 테스트 트랜잭션에 **참여**한다(`REQUIRED`). 서비스가 예외를 던지면 Spring 은 rollback-only 표시만 남기고, 예외 전에 넣은 행은 같은 트랜잭션이라 **테스트에 그대로 보인다.**
「실패하면 아무것도 안 남는다」를 `PostgresTestBase` 위에서 재면 행이 남아 있다고 나온다 — `ReservationHoldTest`·`ReservationCancelTest` 가 그렇게 빨개졌다.

**커밋·롤백 자체를 재는 단언은 커밋 레인(`ConcurrencyTestBase`)에 둔다.** 아래 지연 트리거와 같은 부류다.

### `REQUIRES_NEW` 를 쓰는 코드는 롤백 레인에서 못 잰다

새 트랜잭션은 **테스트 트랜잭션의 미커밋 데이터를 못 본다.** 그 안에서 남의 표를 읽으면 0행이고, 그 코드가 예외를 삼키면 **아무 일도 안 일어난 것처럼 보인다.**

알림 소비자(26)가 그 자리다 — `NotificationStore.record` 가 `REQUIRES_NEW`(릴레이를 안 멈추려고) 인데 본문을 채우려고 예매·좌석·티켓을 읽어서,
롤백 레인에서는 알림이 통째로 안 생겼다. `NotificationIdempotencyTest` 를 커밋 레인으로 옮겼다.

**`AuditLogWriter.detached` 는 같은 `REQUIRES_NEW` 인데 안 걸린다** — 감사 행을 넣기만 하고 남의 표를 안 읽어서다.
**가르는 것은 전파가 아니라 「그 트랜잭션이 밖의 데이터를 읽나」다.**

### 지연 제약 트리거는 롤백하는 테스트에서 한 번도 안 돈다

`deferrable initially deferred` 는 **커밋 시점에** 검사한다. 롤백 테스트에는 그 시점이 없어서 **검사가 한 번도 안 돈 채 전부 초록**이다 — 트리거를 아무리 틀리게 짜도 안 잡힌다.

두 길이 있다: 트랜잭션 안에서 `set constraints all immediate` 를 부르거나, 커밋 레인에서 잰다. 이 저장소는 **커밋 레인**이다 — `ReservationSeatConsistencyTest`·`RefundInvariantTest` 가 `V7`·`V10` 의 지연 트리거 셋(합계·응답·환불 등식)을 거기서 잰다.
**「초록이니까 그 제약이 돈다」가 지연 트리거에는 성립하지 않는다.** 새로 걸면 커밋 레인 테스트가 있는지부터 본다.

트리거가 `raise exception` 으로 떨어뜨리면 SQLSTATE 가 `P0001` 이라 Spring 이 `UncategorizedSQLException` 으로 준다 — `DataIntegrityViolationException` 이 아니다. 둘을 같이 받으려면 `DataAccessException`, 문구로 잡으려면 `hasStackTraceContaining`.

### 지연 트리거 안에서 `NEW` 는 커밋 시점의 값이 아니다

**`NEW` 는 그 트리거를 걸어 준 문장 시점의 행이다.** 같은 트랜잭션에서 넣고 고치는 흐름이면 이 차이가 결과를 뒤집는다.

```
insert (컬럼 = null)   ← 트리거 예약. NEW 에 null 이 박힌다
update (컬럼 = 값)     ← 트리거 또 예약. 이건 통과한다
commit                 ← 앞의 예약분이 null 로 터진다
```

**`after insert` 를 같이 걸어 두면 `NEW` 를 믿는 트리거는 절대 통과할 수 없다.** 행을 다시 읽고, 지워졌으면 검사할 것이 없다 — `V7` 의 `reservation_total_matches_seats`·`idempotency_key_has_response`, `V10` 의 `refund_amounts_match_payment` 가 그 모양이다.

### `now()` 는 트랜잭션 시작 시각이라 롤백 테스트 안에서는 `updated_at` 이 안 움직인다

`set_updated_at` 이 `now()` 를 쓰는데 Postgres 의 `now()` 는 **트랜잭션 시작 시각**이다(`clock_timestamp()` 가 실제 시각).
`@Transactional` 테스트 안에서 오픈하고 바로 갱신하면 두 `updated_at` 이 같다 — `max(updated_at)` 을 버전으로 쓰는 `SeatQuery` 가 그래서 `SeatVersionTest` 를 `ConcurrencyTestBase` 위에 둔다.
트리거를 `clock_timestamp()` 로 바꾸지 않는다 — 한 트랜잭션의 행들이 같은 시각을 갖는 것이 `updated_at` 의 뜻이다.

같은 이유로 **「관람일 N일 전」 같은 시각 판정을 롤백 테스트에서 지나가게 만들 수 없다** — `held_until = now() - interval '1 second'` 처럼 이미 지난 값을 직접 넣는다(`D8`).

### `set_updated_at` 트리거 때문에 시각을 되돌릴 수 없다

「오래된 행」을 만들려고 `update … set updated_at = :old` 를 하면 **트리거가 다시 `now()` 로 덮어쓴다.** `insert` 에 값을 주면 된다(트리거가 `before update` 에만 걸려 있다).
`held_until`·`paying_until` 은 트리거가 없어서 `update` 로 되돌아간다 — 만료 테스트가 그 길을 쓴다.

### 롤백을 끈 테스트는 정리도 한 트랜잭션이어야 한다

`ConcurrencyTestBase` 위에서는 정리 SQL 도 **문장마다 커밋된다.** 자식만 지운 순간 지연 트리거가 중간 상태를 보고 정리가 통째로 실패한다.
정리를 `TransactionTemplate` 하나로 묶고, **`restrict` 외래키를 거슬러 자식부터** 지운다(`ConcurrencyTestBase.purgePrefixedRows` — 티켓 → 환불 → 결제 → 예매 → 회차 → 공연 → 기획사 → 공연장 → 계정).
**앞뒤로 한 번씩** 한다 — 앞선 실행이 죽으면 재사용 컨테이너에 데이터가 남아서, 안 지우면 다음 실행의 뒤 테스트가 유일 제약에서 연쇄로 빨개진다(17 에서 18개가 그렇게 빨개졌다).

### Postgres 의 데드락은 `DeadlockLoserDataAccessException` 이 아니다

`40P01` 이 **`PessimisticLockingFailureException`** 으로 온다. Spring 은 SQLSTATE 앞 두 자리(`40` = 트랜잭션 롤백)로 번역해 상위 타입에 멈춘다.
**재시도를 붙일 때 예외 이름으로 잡으면 데드락을 놓친다.** SQLSTATE 를 직접 본다 — `ReservationService.retryOnceOnDeadlock` 이 원인 사슬에서 `SQLException.sqlState` 를 꺼낸다.

### 유일 위반은 트랜잭션을 어보트시킨다 — `on conflict do nothing` 으로 받는다

유일 인덱스 위반을 예외로 받으면 그 트랜잭션은 `25P02` 라 다음 문장이 못 돈다. 「이미 있으면 그 행을 읽어 답한다」는 흐름은 예외가 아니라 **`on conflict … do nothing` 의 0행**으로 받는다 —
`SeatHoldService.insertReservation`(`duplicate-hold` 에 기존 예매 id), `TicketService.issueOne`(번호 재추첨), `IdempotencyService.claim`.
`on conflict` 의 `where` 는 부분 인덱스의 조건과 **글자까지 같은 리터럴**이어야 한다 — 바인딩하면 플래너가 인덱스를 못 맞춘다.

### 부분 유일 인덱스가 앱 검증을 경합에서 지킨다

`reservation_live_hold_idx`(계정·회차당 살아있는 선점 하나)가 있으면 같은 계정의 둘째 선점은 **첫째의 커밋을 insert 에서 기다린다.** 그 사이 첫째가 센 「이미 잡은 매수」는 다른 트랜잭션이 못 바꾼다.
앱 검증(3위 강제 지점)이 경합에 안전한 이유가 인덱스(2위)에 있다 — `V8`.

### `query(클래스)` 는 컬럼명으로 생성자 인자를 맞춘다

`JdbcClient.query(T::class.java)` 는 snake_case 컬럼을 camelCase 인자로 맞춘다. **이름이 다르면 「열 이름 … 을 찾을 수 없습니다」로 죽는다** — `select fee_amount as fee` 처럼 별칭을 준다.
소문자 저장값을 열거형 필드로 받으면 `Enum.valueOf` 가 못 찾는다 — `String` 으로 받고 `of()` 로 바꾼다(`PerformanceSeatStatus.of`).
`.list()` 의 원소는 Java 에서 와서 Kotlin 이 `T?` 로 본다 — 널일 수 없는 조회면 `filterNotNull()`(`D14` 「플랫폼 타입」).

### `singleRow()` 는 `timestamptz` 를 `java.sql.Timestamp` 로 준다

`Map<String, Any>` 안의 시각은 `OffsetDateTime` 이 아니다. 시각 컬럼은 `query(T::class.java)`(data class 에 `OffsetDateTime`) 나 RowMapper 의 `rs.getObject(name, OffsetDateTime::class.java)` 로 받는다.

### 뷰는 표에 컬럼이 늘어도 안 따라온다

Postgres 는 `create view` 시점의 컬럼 목록을 굳힌다. `select t.*` 로 썼어도 그 순간의 컬럼으로 펼쳐져 저장된다. 표에 컬럼을 더하는 마이그레이션은 뷰를 `drop` 하고 다시 만들어야 한다 —
`create or replace view` 는 컬럼을 뒤에 더할 때만 되고 순서를 바꾸거나 중간에 끼우면 거부한다. 아직 뷰가 없다 — 정산(27)이 처음 만들 자리다.

### `LocalTime.MAX` 를 `timestamptz` 에 넣으면 다음날이 된다

`23:59:59.999999999` 는 나노초까지고 Postgres 는 마이크로초까지만 담고 나머지를 올린다. 저장된 값은 다음날 `00:00:00` 이다.
시각으로 비교하는 코드는 멀쩡하고 **날짜로 되돌리는 코드만 틀린다.** 「말일」이 필요하면 다음날 자정 미만(`< next_day`)으로 쓴다. `D7` 이 KST 달력일로 세는 자리(`RefundPolicy`)가 이것을 만난다.

### 시드를 마이그레이션에 넣으면 다음 마이그레이션이 막힌다

ProjectShop 이 시드를 `V900` 대로 뒀더니 **적용 이력의 최고 버전이 900** 이 됐다. 그 뒤 낮은 번호를 더하면 Flyway 가 순서를 어긴 것으로 보고 기동을 막는다 —
`Detected resolved migration not applied to database: N`. `out-of-order` 를 켜면 진짜 순서 사고도 같이 통과하므로 안 켠다.

**이 저장소는 그 길을 안 간다**(12) — `local` 프로필의 `DemoSeeder` 가 기동 뒤에 서비스를 불러 만든다. Flyway 이력이 안 더럽혀지고, 시드가 실제 경로를 밟아서 그 경로가 도는지도 같이 확인된다.
대신 **멱등을 스스로 들어야 한다** — Flyway 가 공짜로 주던 「한 번만」이 없어져서, 러너가 이미 시드된 DB 를 알아보고 건너뛴다.

### 동의 항목·정책 행은 「지금 판」을 골라야 한다

개정판을 **미리 넣어 두고 시행 시각에 갈아 끼우는** 설계라 표에는 아직 시행 안 된 판이 같이 들어 있다. `version` 이나 최신 `id` 로 고르면 시행 전 판을 집는다 — 고르는 기준은 언제나 `effective_at <= now()` 인 최신이다.
동의 항목(`ConsentService.currentItems`)·환불 구간표(`RefundTransitionService.tierRateFor`)·정산 정책(27)이 같은 규칙이다. 시행 전 판이 없는 동안에는 어느 방법이든 같은 답을 줘서 **개정판을 처음 넣는 날까지 아무도 모른다.**

### `csrf()` 후처리기가 공유 필터 체인의 저장소를 바꿔 끼운다

`SecurityMockMvcRequestPostProcessors.csrf()` 는 요청 하나에만 걸리는 것이 아니다. 공유 `CsrfFilter` 의 저장소를 `TestCsrfTokenRepository`(안은 언제나 세션 기반)로 갈아 끼우고 **그 바꿔치기가 스프링 컨텍스트에 남는다.**
그래서 `csrf()` 를 쓰는 클래스가 먼저 돈 실행에서는 평범한 `GET` 이 `XSRF-TOKEN` 쿠키를 안 내린다 — 클래스 순서를 타서 증상이 간헐이다.
Spring Security 가 이 리포트(spring-security#12813)를 의도된 동작으로 닫았다. `CsrfCookieTest` 가 `@BeforeEach` 에서 우리 저장소를 되돌린다. **MockMvc 로는 쿠키 발급을 검증할 수 없다** — 그건 기동한 서버로 본다.

### SPA 에 CSRF 쿠키를 내주려면 두 군데를 더 손봐야 한다

`CookieCsrfTokenRepository.withHttpOnlyFalse()` 만 걸면 쿠키가 안 나간다. 토큰이 지연 생성이라 **아무도 안 읽으면 안 나가고**, `XorCsrfTokenRequestAttributeHandler` 는 쿠키의 평문을 헤더로 되돌려 보내면 거부한다.
`SecurityConfig` 가 토큰을 한 번 읽는 필터를 두고, 헤더로 온 값은 평문으로 비교한다.

### MockMvc 로 실제 로그인을 하면 다음 테스트 클래스가 인증된 채로 시작한다

컨트롤러가 `SecurityContextHolder.setContext()` 를 부르면 그 값이 스레드에 남고, MockMvc 는 테스트들이 스레드를 나눠 쓴다. **기동한 서버에서는 안 난다.**
`PostgresTestBase.clearSecurityContext` 가 `SecurityContextHolder` 와 `TestSecurityContextHolder`(`with(user(...))` 가 심은 것) 둘 다 비운다. 테스트마다 손으로 붙이지 않는 이유는 빠뜨렸을 때 깨지는 것이 **남의 클래스**라서다.

### CSRF 거부가 MockMvc 와 기동한 서버에서 다르게 나온다

토큰 없는 POST 를 열린 경로에 보내면 **MockMvc 는 403, 기동한 서버는 401** 이다. MockMvc 테스트는 상태 코드를 못박지 말고 `is4xxClientError()` 로 둔다.

### 보안 필터의 401 은 예외 처리기가 못 잡는다

인증 실패는 `AuthenticationEntryPoint` 가 MVC 에 닿기 전에 응답을 끝낸다. 본문을 그 자리에서 직접 써야 한다 — `ProblemEntryPoint`·`ProblemAccessDeniedHandler` 가 `ProblemFactory` 하나로 만든다.

### `@RestControllerAdvice` 만으로는 프레임워크 예외를 못 잡는다

`spring.mvc.problemdetails.enabled=true` 를 켜면 Spring 이 자기 핸들러를 먼저 등록한다. 검증 실패·깨진 JSON·지원 안 하는 메서드가 전부 그쪽으로 가서 `@ExceptionHandler` 를 적어 둬도 안 불린다.
**`ResponseEntityExceptionHandler` 를 상속하고 재정의해야** 우리 `type`·`trace_id` 가 걸린다(`ApiExceptionHandler`). `MissingRequestHeaderException` 도 그쪽이라 필수 헤더는 `required = false` 로 받고 직접 400 을 낸다(`IdempotencyKeys`).

### `MockMvc` 의 `Content-Type` 은 charset 이 붙는다

`application/problem+json` 을 기대하면 `;charset=UTF-8` 이 붙어 어긋난다. `contentTypeCompatibleWith` 로 타입만 본다.

### `@BeforeTransaction`·`@AfterTransaction` 은 `@Nested` 클래스에서 안 돈다

Spring 은 그 표시를 테스트 클래스의 상속 계층에서 찾는데 중첩 클래스는 바탕을 상속하지 않는다. 트랜잭션 밖 정리는 **`@BeforeEach` 안에서 `REQUIRES_NEW` 로 연다**(`PostgresTestBase.purgeCommittedAuditLogs`).
뒤가 아니라 앞에서 지운다 — 뒤에서 지우면 아직 커밋 안 된 그 테스트의 행을 다른 트랜잭션이 지우려 드는 모양이 돼서 잠금에 걸린다.

### 컨테이너 재사용은 코드가 아니라 로컬 파일이 켠다

`.withReuse(true)` 가 코드에 있어도 기계마다 `~/.testcontainers.properties` 에 `testcontainers.reuse.enable=true` 가 있어야 한다. 안 켜져 있어도 실패하지 않고 경고 한 줄과 함께 새로 띄운다.
CI 러너에서는 효과가 없다. **마이그레이션을 고쳤으면 재사용 컨테이너를 지운다** — Flyway 체크섬이 안 맞아 전부 빨개진다.

### `@ServiceConnection` 컨테이너는 Spring 컨텍스트마다 뜬다

`Containers` 가 `@TestConfiguration` 이라 **컨텍스트가 갈리면 컨테이너도 따로 뜬다.** 컨텍스트 캐시 키는 애너테이션으로 갈리므로 `ConcurrencyTestBase` 가 `PostgresTestBase` 와 애너테이션을 똑같이 맞춘다(`@AutoConfigureMockMvc` 까지) — `@Transactional` 만 캐시 키에 안 들어간다.
재사용을 켜면 갈린 컨텍스트도 같은 컨테이너에 붙는다.

### `GenericContainer` 는 `@ServiceConnection` 에 이름을 적어야 한다

이미지 이름(`redis:7-alpine`)만으로 알아보는 길이 Kotlin 의 `GenericContainer<Nothing>` 에서는 안 먹었다 — `@ServiceConnection(name = "redis")` 로 적어야 붙는다.
안 적으면 기동이 `ConnectionDetailsNotFoundException` 으로 죽고, 문구가 「You may need to add a 'name'」이라 그대로 따르면 된다(`20a` 에서 겪었다).
Testcontainers 2.x 에는 Redis 전용 모듈이 없어서 `GenericContainer` 를 쓸 수밖에 없다.

### Spring Session 색인은 기동 때 Redis 설정을 바꾼다

`spring.session.redis.repository-type: indexed` 면 저장소가 뜰 때 Redis 에 `CONFIG SET notify-keyspace-events` 를 보낸다(만료 세션을 색인에서 걷어내려고).
`CONFIG` 를 막아 둔 Redis 에서는 기동이 실패한다. 그때는 `ConfigureRedisAction.NO_OP` 을 빈으로 두고 서버 쪽 설정을 손으로 켠다.

### 컨테이너로 띄우면 로그 파일 자리가 없다

`logback-spring.xml` 이 `logs/ticket.log` 를 상대 경로로 여는데, 이미지가 비루트 사용자로 돌면 `/app` 이 root 것이라 기동이 죽는다 —
증상은 Logback 스택 트레이스와 무한 재시작이다(33 에서 겪었다). `Dockerfile` 이 `mkdir -p /app/logs && chown` 을 한다.

### 최소 15자면 유출 목록이 거의 안 걸린다

SecLists 의 `10k-most-common` 10,001개 중 **15자를 넘는 것은 하나뿐**이다(`films+pic+galeries`).
길이 규칙이 이미 그 목록을 막고 있어서, 블록리스트가 실제로 잡는 것은 **흔한 것을 늘려 만든 것**이다 —
`passwordpassword`·`123456789012345`. 그래서 목록은 대조용이자 **반복 검사의 사전**으로 쓴다(`3b`).

### `DataSource` 빈을 하나 더 만들면 기본 자동설정이 꺼진다

Boot 의 `DataSourceAutoConfiguration` 은 `@ConditionalOnMissingBean(DataSource)` 다 — **타입으로 본다.**
파기 전용 풀을 빈으로 하나 만들었더니 기본 DataSource 가 아예 안 생기고 **앱 전체가 그 연결을 썼다**(4a 에서 겪었다).
연결을 가르고 싶으면 기본 빈도 같이 직접 정의하거나, 아예 **같은 연결에서 `set local role`** 로 가른다(지금 방식).

### 실패한 트랜잭션은 그 뒤 문장을 전부 거절한다

`current transaction is aborted, commands ignored until end of transaction block`. 한 테스트에서 **거절을 두 번 재려면**
저장점이 필요하다 — `TransactionTemplate` 에 `PROPAGATION_NESTED` 를 주면 실패가 저장점까지만 되돌아가고 `SET LOCAL` 도 같이 풀린다.

### detekt 1.23 은 JDK 25 에서 안 돈다

묶여 있는 IntelliJ 유틸이 `25.0.1` 이라는 판 문자열을 못 읽어 `IllegalArgumentException: 25.0.1` 로 선다.
`jvmTarget` 을 낮춰도 소용없다 — 분석기가 **도는** JVM 이 문제고, detekt 의 Gradle 태스크는 그 JVM 을 바꿀 자리를 안 준다.
그래서 2.0 알파(`dev.detekt`, 좌표가 바뀌었다)를 쓴다.

**detekt 는 자기가 빌드된 Kotlin 으로만 돈다.** 우리 판과 다르면 「compiled with X but running with Y」로 선다 —
`configurations.named("detekt")` 에서 detekt 쪽 Kotlin 의존만 그 판으로 고정한다(2.0.0-alpha.6 은 2.4.10).
우리 코드가 컴파일되는 판은 그대로다.

### Lua 는 큰 정수를 지수 표기로 접는다

Redis 스크립트 안에서 `1789699665760900 .. ''` 같은 잇기를 하면 `1.7896996657609e+15` 가 된다 — Lua 5.1 의 수가 double 이고 기본 서식이 `%.14g` 라서다.
좌석 판·스트림 id 처럼 **정수를 문자열로 만들 때는 `string.format('%d', n)`** 을 쓴다. 안 쓰면 파싱이 `NumberFormatException` 으로 죽고,
그 값이 스트림 id 면 애초에 XADD 가 거절한다(`10a` 에서 겪었다).

### Gradle 은 Test 태스크를 up-to-date 로 건너뛴다

입력이 같으면 두 번째 실행은 안 돈다. 숫자를 내는 측정(`gradlew measure`)은 그러면 **지난 표를 이번 것으로 읽게 된다** — `outputs.upToDateWhen { false }` 로 늘 돌린다(19 에서 두 번째 실행이 첫 표와 같아서 알았다).

### KDoc 안의 `/**` 는 주석을 다시 연다

**Kotlin 은 블록 주석이 중첩된다.** KDoc 에 경로 패턴(`/api/organizer/**`)을 쓰면 그 `/*` 가 **안쪽 주석을 열고**, 끝의 `*/` 가 그것만 닫는다 —
바깥 주석이 파일 끝까지 안 닫혀서 `Syntax error: Unclosed comment` 가 **마지막 줄**을 가리킨다. 진짜 자리는 한참 위다.

백틱 안이어도 마찬가지다 — 컴파일러는 마크다운을 모른다. 경로를 적을 때 `**` 를 `…` 로 바꾼다.

### Windows 에서 만든 실행 파일은 실행 비트가 없다

로컬에서는 영원히 안 드러난다. git 은 `100644` 로 들고 있고 리눅스 러너에서만 `Permission denied`(exit 126)가 난다.

```
git ls-files -s backend/gradlew        # 100644 이면 실행 비트가 없다
git update-index --chmod=+x backend/gradlew
```

셸 스크립트를 더하는 청크는 그 자리에서 모드를 확인한다.

### 한글이 든 본문을 `curl -d` 로 보내면 400 이 난다

이 환경의 Git Bash 가 명령줄 인자를 UTF-8 로 안 넘긴다. 증상이 「요청 형식이 맞지 않는다」 하나뿐이라 필드가 틀린 것처럼 보인다.
파일로 두고 `--data-binary @body.json` 으로 보낸다. 파일은 Write 도구로 만든다 — 셸 heredoc 도 같은 자리에서 깨진다.
**heredoc 은 백슬래시도 먹는다** — `\\d` 가 `\d` 로 들어간다. 정규식이 든 소스는 Write 도구로 쓴다.

### Git Bash 가 `origin/main:.claude/…` 인자를 경로로 바꿔 버린다

MSYS 경로 변환이 `ref/x:.dir/file` 꼴(슬래시 든 ref + 점으로 시작하는 경로)을 경로 목록으로 읽어 고친다. `git rev-parse -q --verify` 가 오류 없이 빈손으로 끝나 「그 경로 없음」과 구별이 안 된다.
`HEAD:.claude/…` 는 멀쩡해서 한쪽만 틀린다 — 옛 `verify-fingerprint.sh` 가 이것으로 윈도에서 backend·tools 레인을 늘 「바뀜」으로 셌다(`G1`).
`ref:path` 인자를 안 쓴다 — `git ls-tree <ref> -- <path>` 로 받거나, 꼭 써야 하면 `MSYS_NO_PATHCONV=1` 을 앞에 둔다.

### 훅이 산문을 명령으로 읽는 자리가 둘이다

① 훅 입력은 JSON(`{"tool_input":{"command":"…"}}`)이라 원문에 `grep` 을 걸면 설명문까지 읽힌다 — 명령만 꺼내고 본다.
② 꺼낸 뒤에도 커밋 메시지 같은 산문이 명령 문자열 안에 진짜로 있다 — 명령 위치에 앵커(`(^|[;&|])[[:space:]]*`)를 건다.

### hook `matcher` 는 터미널이 아니라 도구 이름이다

`"matcher": "Bash"` 는 Claude Code 의 `Bash` 도구에만 걸린다. 같은 명령을 `PowerShell` 도구로 보내면 그 훅은 안 돈다. 게이트는 `Bash|PowerShell` 로 적고 명령 패턴도 양쪽 어휘(`sed -i`·`Set-Content`)를 든다.

### 필수 검사는 이름으로 붙지 이벤트를 안 가린다

가지 보호의 필수 검사는 `{context, app_id}` 로 맞춘다. `push` 와 `pull_request` 를 둘 다 걸면 같은 커밋을 두 번 검사하고 `concurrency` 가 못 막는다(그룹 키 `github.ref` 가 다르다).
필수 검사 넷이 전부 `ci.yml`(`backend`·`frontend`·`secrets`·`docs`)에 있다 — 그 파일의 트리거를 건드릴 때 이 문단을 본다. fork 에서 온 PR 은 base 에 push 가 안 돌므로 바깥 PR 을 받기 시작하면 `pull_request:` 를 되돌린다.

### `main` 가지 보호는 admin 을 기본으로 안 막는다

`enforce_admins` 가 `false` 면 저장소 주인은 그대로 민다. `gh api -X POST repos/<소유자>/<이름>/branches/main/protection/enforce_admins` 로 켠다. 켜져 있다(「현재 상태」).

### Dependabot 경보는 가지에 밀어도 안 닫힌다

의존 그래프가 기본 가지 기준이라 작업 가지에 올려도 경보가 그대로다. `main` 에 머지돼야 `fixed` 로 닫힌다. 오탐은 사람이 `inaccurate` 로 닫는다.

### Redis 는 테스트 롤백이 안 되돌린다

`PostgresTestBase` 가 `@Transactional` 이라 DB 는 테스트마다 깨끗한데 Redis 에 쓴 것은 남는다. 그래서 그 바탕이 `@BeforeEach` 에서 `flushDb()` 를 부른다(`20a`) — 세션은 계정 이름으로 색인돼서, 남기면 다음 테스트가 남의 세션을 자기 것으로 센다.


### 커밋 레인이 남긴 행은 롤백 레인에 **보인다**

방향을 헷갈리기 쉽다. 롤백 레인이 만든 것은 남이 못 보지만, **커밋 레인이 커밋한 것은 롤백 레인이 본다.**
전역을 세는 단언(「릴레이가 1건 집었다」)이 그 자리에서 깨진다 — `OutboxRelayTest` 가 `ConsumerIdempotencyTest` 의
안 나간 outbox 행까지 집었다(28·29 뒤로 생긴 자리).

**로컬은 컨테이너를 재사용해서 안 보인다.** 지난 회차가 이미 치워 둔 탓이고, CI 의 새 컨테이너에서만 빨갛다.
전역을 세지 말거나, 세야 하면 `@BeforeEach` 에서 남은 것을 옆으로 치운다.

### `initdb.d` 는 볼륨이 비었을 때만 돈다

`docker-compose.yml` 의 `/docker-entrypoint-initdb.d` 는 데이터 디렉터리가 비어 있을 때 한 번만 실행된다. 파일을 넣어도 기존 볼륨에서는 아무 일이 안 나고 오류도 없다 — `docker compose down -v && up -d --wait`. **Testcontainers 는 이 경로를 안 태운다.**

### nginx `upstream` 은 이름을 기동 때 한 번만 푼다

`server app:8080;` 은 compose 가 `--scale` 로 대수를 늘려도 **첫 A 레코드 하나만** 쥔다 — 셋을 띄우고 한 대가 다 받는다(35 가 부하에서 찾았다).
매 요청에 다시 풀려면 `resolver 127.0.0.11` 을 두고 `proxy_pass` 의 호스트를 변수로 적는다. 대가는 `upstream` 블록(keepalive·죽은 대 건너뛰기)을 못 쓰는 것이다.

### JRE 이미지에는 `wget` 도 `curl` 도 없다

`eclipse-temurin:*-jre` 기준이다. compose 의 `healthcheck` 에 그대로 적으면 `/bin/sh: 1: wget: not found` 로 **컨테이너만 unhealthy** 고 앱은 멀쩡하다.
Dockerfile 에서 하나를 깔거나, 셸 없이 되는 방법으로 바꾼다.

### CodeQL 의 Kotlin 추출기는 컴파일러 안에서 돈다 — 데몬 힙이 같이 터진다

`build-mode: manual` 로 `./gradlew classes testClasses` 를 돌리면 추출기가 별도 프로세스가 아니라
**Kotlin 컴파일러 프로세스에 붙어서** 돈다. 기본 힙으로는 `compileKotlin` 이 10분을 쓰고
`e: java.lang.OutOfMemoryError: GC overhead limit exceeded` 로 죽는다(run 35427637052).
**같은 러너에서 `ci.yml` 의 `./gradlew build` 는 초록이라 코드 크기 문제가 아니다** — 추출기 몫이다.
`codeql.yml` 의 그 step 에만 `-Pkotlin.daemon.jvmargs=-Xmx4g` 를 준다. `gradle.properties` 로 내리면
안 죽는 레인의 메모리까지 같이 바꾼다.

### 화면 쪽

| 자리 | 사실 |
|---|---|
| `next/image` | `images.remotePatterns` 에 없는 호스트는 통째로 거부한다. 우리 서버가 남의 이미지를 대신 내려받는 통로가 되지 않게 하는 기본값이다 |
| `.next/types` | 빌드 산출물인데 `tsconfig` 가 `include` 한다. `next build` 가 중간에 죽으면 `tsc --noEmit` 이 없는 페이지를 가리켜 빨갛다 — `rm -rf .next` 뒤 `npx next typegen` |
| `notFound()` | 화면 안에서 부르면 UI 는 404 인데 HTTP 는 **200** 이다 — 스트리밍이 시작된 뒤라 상태를 못 바꾼다. 진짜 404 는 스트리밍 전(`proxy`)에서 |
| `npm run dev` | 죽여도 3000 을 쥔 node 가 남고 낡은 서버가 답한다. HMR 이 새 코드를 도는 것처럼 보이게 한다 |
| `vitest-axe` | 정식 판이 없고 vitest 최신과 안 붙는다. `axe-core` 를 직접 쓴다 |
| 브라우저로 밟기 | Puppeteer 가 사용자 홈(`C:\Users\EJG\node_modules\puppeteer`)에 있다. 저장소에 안 넣는다. 경로는 `file://` URL 로 준다 — POSIX 경로를 그대로 넘기면 Node 가 `C:\c\Users\…` 로 읽는다 |
| `rewrites()` | **`next build` 때 굳는다.** `next.config.ts` 가 읽은 `BACKEND_ORIGIN` 이 `routes-manifest.json` 에 박혀서 시작할 때 주는 env 로는 안 바뀐다 — 다른 주소를 보게 하려면 그 값을 주고 **다시 빌드**한다(`39` 실측: 8080 이 박힌 채로 8081 을 줘서 프록시가 500 이었다) |
| 비밀번호 블록리스트 | 가입을 손으로 밟을 때 `password` 가 든 문자열은 15자를 넘겨도 막힌다(`PasswordBlocklist`). 400 이 `validation-failed` 로 나온다 |
| Puppeteer 진입점 | `lib/puppeteer/puppeteer.js` 다. `lib/esm/...` 은 없다 |
| 접근성 헛것 | 이름 없는 `<input>` 은 `page.accessibility.snapshot()` 으로 브라우저에게 묻고, `<nextjs-portal>`(개발 오버레이)은 걷어낸다 — 판정을 뒤집기 전에 재는 도구부터 의심한다 |

## 데이터 접근은 `JdbcClient` 다

**JPA 를 안 쓴다.** `spring-boot-starter-jdbc` 만 들이고 엔티티는 하나도 안 만든다(ADR 0001). 좌석 선점이 조건부 UPDATE 의 갱신 행 수로 판정하는 코드라(`D4`) SQL 이 그대로 보여야 하고,
스키마를 지키는 것은 Flyway 와 마이그레이션이다. 의존성 목록이 설계를 안 속이게 한다 — `data-jpa` 스타터를 들이면 다음 사람이 Hibernate 가 도는 줄 안다.

## 프론트에서 안 쓰는 것

관례로 깔리는 넷을 안 쓴다. 근거가 없으면 다음 사람이 빠뜨린 줄 알고 채워 넣는다.

| 안 쓰는 것 | 관례상 기본 | 왜 안 쓰나 |
|---|---|---|
| 서버 상태 관리 | `@tanstack/react-query`·`swr` | 서버 컴포넌트가 기본이라 캐시 계층이 겹친다(`D16`). 좌석도 폴링은 `D20` 의 ETag·델타가 그 자리다 |
| 폼 | `react-hook-form` | 칸이 적고 검증이 서버가 유일한 출처다. 비제어 `FormData` 로 충분하다 |
| 날짜 | `date-fns`·`dayjs` | 로케일 하나(`ko-KR`), 시간대 하나(`Asia/Seoul`). `toLocaleDateString` 이 그 둘을 받는다 |
| HTTP | `axios` | 입구가 `api.ts` 하나고 인터셉터로 할 일(표기 변환·CSRF·401)을 거기서 한다 |

넷 다 값이 오르면 다시 본다.

## 아직 안 정한 것

| 대상 | 언제 |
|---|---|
| Redisson(스케줄러 락·좌석 락) | 33, ADR 0003. Lettuce 는 `20a` 가 스타터로 들였다 |
| 품질 게이트 도구(detekt·CodeQL·SpotBugs) | `P10`(46·47). ProjectShop 의 SpotBugs·find-sec-bugs 는 `JdbcClient` 를 몰라 SQL 조립을 못 봤다 — 그 판단은 그때 다시 |
| Kafka | 28, ADR 0007 |

정해지면 위 버전표에 줄을 더한다.

## 버전을 올릴 때

| 대상 | 올리면 볼 곳 |
|---|---|
| Spring Boot | 스타터 이름과 자동설정 패키지. 마이너 버전에서도 바뀐 전례가 있다. Kotlin 플러그인 버전을 BOM 값에 맞춘다 |
| Testcontainers | Docker Engine 최소 지원 API 버전 |
| PostgreSQL | `transaction-iso` 문서. `D4` 가 Read Committed 의 `WHERE` 재평가에 기대고 있다 |
| Java | Gradle toolchain 과 CI 의 JDK |

**올린 뒤에는 `verify.sh --full` 을 실제로 돌린다.** 돌리지 않았으면 그렇게 적는다.

## 이 문서를 고칠 때

새 의존성이 들어오면 위 버전표에 줄을 더한다 — `StackVersionConsistencyTest` 가 파일과 대조한다.
**기억과 실제가 어긋난 경험이 생기면 「기억으로 쓰면 틀리는 자리」에 적는다.** 그게 이 문서의 값이다.
