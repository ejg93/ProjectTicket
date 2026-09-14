# 스택과 버전

> **ProjectShop 에서 이식한 문서다.** 예시·청크 번호(`2g`·`Q36` 등)·쇼핑 도메인(주문·셀러·정산)은 ProjectShop 것이다.
> 티켓 도메인과 Kotlin 에 맞추는 것은 `PLAN.md` 이식 청크 `P` 줄이 한다. 그 청크가 닫히면 이 머리말을 지운다.
> 그때까지는 **원칙은 믿고 예시는 안 믿는다.**

무엇을 어느 버전으로 쓰는지 적고, **기억으로 쓰면 틀리는 자리**를 표시한다.

## 이 문서는 API 레퍼런스가 아니다

메서드 이름이나 설정 키를 여기 옮겨 적지 않는다. 두 가지가 걸린다.

- 옮겨 적는 사람이 그 버전을 정확히 모르면 **틀린 것이 문서로 굳는다.** 다음 사람은 그걸 근거로 믿는다
- 라이브러리가 올라가면 낡는데, 낡은 줄 모르고 본다

API 가 필요하면 아래 공식 문서를 연다. **여기 적는 것은 "어디를 봐야 하나" 와 "무엇을 조심하나" 뿐이다.**

## 버전

| 대상 | 버전 | 어디에 박혀 있나 |
|---|---|---|
| Spring Boot | 4.1.1 | `backend/build.gradle.kts` |
| Java | 25 | 같은 파일의 toolchain |
| Gradle | 9.7.1 | `gradle/wrapper/gradle-wrapper.properties` |
| PostgreSQL | 17-alpine | `docker-compose.yml` |
| Redis | 7-alpine | `docker-compose.yml`. 테스트 컨테이너도 같은 이미지다 |
| Tomcat | 11.0.25 | `backend/build.gradle.kts` 의 `tomcat.version`. **BOM 값을 덮었다** — 아래 「Boot BOM 의 Tomcat 이 보안 패치보다 낮을 수 있다」 |
| Testcontainers | 2.0.5 | `build.gradle.kts` 의 BOM |
| springdoc-openapi | 3.1.1 | `build.gradle.kts`. **3.x 가 Boot 4 판이다** — 2.x 는 Boot 3 모듈 배치를 부른다 |
| ArchUnit | 1.5.0 | `build.gradle.kts`. **`archunit-junit6`** 다 — 이 저장소가 JUnit 6 이다 |
| Caffeine | 3.2.4 | 안 적는다. **Boot BOM 이 관리한다** |
| Jackson | 3.1.4 | 안 적는다. `starter-webmvc` 가 딸려 온다 |
| Spring Security | 7.1.0 | 아직 의존성에 없다. 청크 5 에서 들어온다 |

**버전을 물으면 이 표가 아니라 위 파일들을 본다.** 표가 낡을 수 있다.

## 공식 문서

| 대상 | 링크 |
|---|---|
| Spring Boot | https://docs.spring.io/spring-boot/index.html |
| Spring Framework | https://docs.spring.io/spring-framework/reference/index.html |
| Spring Security | https://docs.spring.io/spring-security/reference/index.html |
| PostgreSQL 17 | https://www.postgresql.org/docs/17/index.html |
| Testcontainers | https://java.testcontainers.org/ |

설계 근거로 삼은 자료(Zalando·OWASP·Stripe 등)는 `external-references.md` 에 따로 있다.
이 표는 구현할 때 여는 것이다.

## 기억으로 쓰면 틀리는 자리

### `csrf()` 후처리기가 공유 필터 체인의 저장소를 바꿔 끼운다

`SecurityMockMvcRequestPostProcessors.csrf()` 는 편의 기능처럼 보이지만 **요청 하나에만 걸리는 것이 아니다.**
공유 `CsrfFilter` 의 저장소를 `TestCsrfTokenRepository` 로 갈아 끼우고, **그 바꿔치기가 스프링 컨텍스트에 남는다.**

그래서 `csrf()` 를 쓰는 테스트 클래스가 먼저 돈 실행에서는 `CookieCsrfTokenRepository` 가 아예 안 돌고,
평범한 `GET` 이 `XSRF-TOKEN` 쿠키를 안 내린다. **클래스 실행 순서를 타므로 증상이 간헐이다** — 열 번 넘게 돌려 세 번 났다.

잡은 방법이 답을 반이나 줬다. 처음에는 단언이 「쿠키가 없다」 하나뿐이라 무엇이 틀렸는지 못 읽었고,
**실패 문구에 그 순간 `CsrfFilter` 가 든 저장소 이름을 실어서** 다음 실패에 바로 나왔다.

`CsrfCookieTest` 가 `@BeforeEach` 에서 우리 저장소를 되돌린다. **`csrf()` 를 쓰는 다른 테스트를 고치지 않았다** —
그쪽은 CSRF 를 재는 것이 아니라 지나가려는 것이고, 재는 자리가 스스로를 지키는 편이 낫다.

### 테스트가 전부 실패하면 Docker 부터 본다

Docker Desktop 이 꺼져 있으면 **테스트가 하나도 안 통과한다.** `PostgresTestBase` 가
Testcontainers 로 Postgres 를 직접 띄우기 때문이다(`D15`).

읽히는 오류가 코드 문제처럼 생겼다는 것이 함정이다 — 맨 앞에 나오는 것은
`IllegalStateException at DefaultCacheAwareContextLoaderDelegate` 고, Spring 컨텍스트가
안 뜬 이야기라 **방금 고친 코드를 의심하게 된다.** 진짜 원인은 스택 맨 아래
`DockerClientProviderStrategy` 한 줄이다.

```
docker info --format "{{.ServerVersion}}"
```

이것이 실패하면 코드를 보지 말고 Docker Desktop 을 띄운다. 기동에 시간이 걸려서
바로 다시 돌리면 같은 오류가 난다.

### `bootRun` 은 죽여도 8080 을 안 놓는다

`bootRun` 을 배경으로 띄우고 Gradle 쪽 프로세스를 죽여도 **앱의 JVM 은 남는다.**
그 상태에서 `docker compose down -v` 를 하면 살아 있던 앱이 DB 를 잃고,
`/api/health` 가 이렇게 답한다.

```
relation "flyway_schema_history" does not exist
```

**새 마이그레이션이 깨진 것처럼 보이는 것이 함정이다.** 실제로는 방금 띄우려던 앱이
아직 뜨지도 않았고, 답한 것은 지난번 앱이다. 다시 띄우기 전에 포트를 비운다.

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

### Testcontainers 2.x 는 좌표와 클래스가 같이 움직였다

BOM 만 올리면 **`Could not find org.testcontainers:postgresql:`** 로 죽는다. 버전 자리가 비어서
「없는 모듈」처럼 보이는데, 실제로는 **2.x 부터 모듈에 `testcontainers-` 접두어가 붙은 것**이다.

| 1.x | 2.x |
|---|---|
| `org.testcontainers:postgresql` | `org.testcontainers:testcontainers-postgresql` |
| `org.testcontainers:junit-jupiter` | `org.testcontainers:testcontainers-junit-jupiter` |
| `org.testcontainers.containers.PostgreSQLContainer` | `org.testcontainers.postgresql.PostgreSQLContainer` |

**새 클래스는 제네릭이 아니다.** `PostgreSQLContainer<?>` 를 그대로 두면
`does not take parameters` 로 컴파일이 막힌다. 옛 클래스는 남아 있고 **deprecated 경고만** 뜬다 —
경고를 오류로 안 올려 뒀으므로(`build.gradle.kts`) **안 고쳐도 빌드는 지나간다.**

`GenericContainer` 는 자리도 제네릭도 그대로다.

**버전과 좌표는 검색 API 말고 저장소에서 본다.** Maven Central 검색 API 는 색인이 늦어서
2.0.5 를 0건으로 답했다. `repo1.maven.org` 의 `maven-metadata.xml` 과 BOM POM 이 실물이다.

### 뷰는 표에 컬럼이 늘어도 안 따라온다

Postgres 는 `create view` 시점의 컬럼 목록을 굳힌다. `select so.*` 로 썼어도 마찬가지다 —
뷰를 만들 때 `*` 가 그 순간의 컬럼으로 펼쳐져 저장된다.

`seller_order_visible` 이 그 자리다(`V16`). 표에 컬럼을 더하는 마이그레이션은
**뷰를 `drop` 하고 다시 만들어야** 한다. `V26` 이 그것을 빠뜨려서 셀러 조회가
`bad SQL grammar` 로 깨졌다 — 셀러 조회가 이 뷰만 읽기 때문이다(`11c-2b`).

**조용히 틀리지 않고 바로 깨지는 쪽이라** 별도 방벽을 안 뒀다.
`create or replace view` 는 컬럼을 <b>뒤에 더할 때만</b> 되고 순서를 바꾸거나
중간에 끼우면 거부한다 — 그래서 `drop` 후 재생성이 정해진 방법이다.

### Boot 4 는 스타터 이름이 3.x 와 다르다

`build.gradle.kts` 에 실제로 들어 있는 이름이다.

| Boot 4 | 3.x 에서 쓰던 이름 |
|---|---|
| `spring-boot-starter-webmvc` | `spring-boot-starter-web` |
| `spring-boot-starter-flyway` | `flyway-core` 를 직접 넣었다 |
| `spring-boot-starter-webmvc-test` | `spring-boot-starter-test` 하나였다 |

**테스트 스타터가 모듈별로 쪼개졌다.** `-actuator-test`, `-data-jpa-test`, `-flyway-test`,
`-validation-test`, `-webmvc-test` 가 따로 있다.

의존성을 추가할 때 **기억으로 쓰지 말고 `build.gradle.kts` 에 이미 있는 이름의 형태를 따른다.**
여기 없는 스타터가 필요하면 공식 문서에서 확인한다.

여기 적은 셋 말고 무엇이 더 바뀌었는지는 확인하지 않았다.

### Jackson 3 이라 패키지가 `tools.jackson` 이다

Boot 4 는 Jackson 3 을 쓴다. **네임스페이스가 통째로 바뀌었다.**

| Boot 4 (Jackson 3) | 3.x (Jackson 2) |
|---|---|
| `tools.jackson.databind.ObjectMapper` | `com.fasterxml.jackson.databind.ObjectMapper` |
| `tools.jackson.core.JacksonException` | `com.fasterxml.jackson.core.JsonProcessingException` |

`com.fasterxml.jackson.core:jackson-annotations` 는 아직 2.x 로 남아 있어서
**의존성 트리에 두 이름이 같이 보인다.** 애너테이션만 옛 이름이다.

`JacksonException` 은 `RuntimeException` 이라 `throws` 선언이 필요 없다.
Jackson 2 습관으로 checked 예외를 잡으려 하면 컴파일이 안 된다.

### Boot 4 는 자동설정 클래스도 모듈별로 옮겼다

스타터 이름만 갈린 게 아니다. **클래스가 앉은 패키지가 같이 움직였다.**
IDE 자동 완성이 옛 이름을 안 찾아 주고, 오류는 `package ... does not exist` 로만 나온다.

| Boot 4 | 3.x |
|---|---|
| `org.springframework.boot.web.server.autoconfigure.ServerProperties` | `org.springframework.boot.autoconfigure.web.ServerProperties` |
| `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` | `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc` |

규칙이 있다. **기술 이름이 앞으로 나오고 `autoconfigure` 가 뒤로 간다.**
못 찾겠으면 클래스 이름으로 jar 안을 뒤지는 편이 빠르다.

```
unzip -l <jar> | grep ServerProperties.class
```

### SPA 에 CSRF 쿠키를 내주려면 두 군데를 더 손봐야 한다

`CookieCsrfTokenRepository.withHttpOnlyFalse()` 만 걸면 **쿠키가 안 나간다.**
설정은 맞아 보이는데 응답에 아무것도 안 실리고, 오류도 안 난다.

| 걸리는 것 | 증상 | 손볼 곳 |
|---|---|---|
| 토큰이 지연 생성된다 | 아무도 안 읽으면 쿠키가 안 나간다 | 토큰을 한 번 읽는 필터를 `CsrfFilter` 뒤에 넣는다 |
| `XorCsrfTokenRequestAttributeHandler` | 쿠키에서 읽은 값을 헤더에 그대로 실으면 거부된다 | 헤더로 온 값만 평문 비교한다 |

두 번째가 특히 안 보인다. 저장소는 쿠키에 **평문**을 넣는데 XOR 핸들러는 돌아온 값을
인코딩된 것으로 보고 디코딩을 시도한다. 클라이언트는 받은 값을 그대로 보냈는데 403 이 난다.

`SecurityConfig.csrfTokenRequestHandler()` 가 내보낼 때만 XOR 를 쓰고,
헤더로 돌아온 값은 평문으로 비교한다. 폼 파라미터 경로는 그대로 XOR 로 푼다.

### MockMvc 로 실제 로그인을 하면 다음 테스트 클래스가 인증된 채로 시작한다

컨트롤러가 `SecurityContextHolder.setContext()` 를 부르면 그 값이 **스레드에 남는다.**
MockMvc 는 테스트들이 스레드를 나눠 쓰기 때문에 다음 클래스가 그 인증을 물려받는다.

증상이 엉뚱한 데서 난다. `AuthLoginTest` 를 추가했더니 손대지 않은 CSRF 토큰 테스트 여섯이
토큰 쿠키를 못 받아 깨졌다. **기동한 서버에서는 안 난다** — 요청마다 스레드가 갈린다.

`PostgresTestBase` 가 걷어낸다. 테스트마다 손으로 붙이지 않는 이유는 빠뜨렸을 때
깨지는 것이 **빠뜨린 그 클래스가 아니라 남의 클래스**라서다. 원인을 찾을 실마리가 없다.

**비울 곳이 둘이다.** `SecurityContextHolder` 는 로그인 컨트롤러가 심은 것을,
`TestSecurityContextHolder` 는 `with(user(...))` 가 심은 것을 들고 있다. 둘 다 비운다.

### `with(csrf())` 를 한 번 쓰면 그 컨텍스트에서 CSRF 쿠키가 영영 안 나온다

**MockMvc 로는 쿠키 기반 CSRF 발급을 검증할 수 없다.** 프레임워크가 저장소를 갈아치운다.

`SecurityMockMvcRequestPostProcessors.csrf()` 는 설정된 `CsrfTokenRepository` 를
`TestCsrfTokenRepository` 로 감싸는데, **그 안은 언제나 `HttpSessionCsrfTokenRepository` 다.**
`CookieCsrfTokenRepository` 로 설정해 뒀어도 그렇다. 그리고 그 교체가 **공유 Spring 컨텍스트에 남아서**,
같은 컨텍스트를 쓰는 뒤 테스트들은 토큰을 세션에 저장하고 `Set-Cookie` 를 안 보낸다.

순서를 고정해 확인한 결과다.

```
before-any-csrf  cookies=1
used with(csrf())
after-with-csrf  cookies=0     ← 여기서 죽는다
```

**로그인과는 무관하다.** 인증 상태를 비워도 안 고쳐지고, `@DirtiesContext(BEFORE_CLASS)` 로는
고쳐진다 — 컨텍스트에 남은 상태이기 때문이다.

Spring Security 는 이 리포트를 `status: invalid` 로 닫았다. 테스트 인프라의 의도된 동작이다.

**대응은 층을 옮기는 것뿐이다.** 쿠키·세션이 실제로 오가는지 보려면 `HttpTestBase` 를 쓴다.
`with(csrf())` 자체는 POST 를 태우는 정상적인 방법이라 계속 쓴다 — 같은 컨텍스트에서
쿠키 발급까지 검증하려 들지만 않으면 된다.

기동한 서버는 언제나 정상이었다. 프로덕션 결함이 아니다.

### 베이스 클래스의 `@AfterEach` 는 `protected` 여야 한다

package-private 이면 **다른 패키지의 하위 클래스에 상속되지 않고, JUnit 이 조용히 안 부른다.**
오류도 경고도 없다. 정리 코드를 베이스로 올렸는데 아무것도 안 바뀌면 이걸 먼저 본다.

```java
@AfterEach
protected void clearSecurityContext() { ... }
```

### CSRF 거부가 MockMvc 와 기동한 서버에서 다르게 나온다

토큰 없는 POST 를 열린 경로에 보내면 **MockMvc 는 403, 기동한 서버는 401** 이다.
같은 필터 설정에서 갈린다. 이유는 안 밝혔다.

**실제 답은 401 이다.** `HttpFlowTest` 가 진짜 HTTP 로 확정했다.
MockMvc 쪽 테스트는 상태 코드를 못박지 말고 `is4xxClientError()` 로 둔다.

### `singleRow()` 는 `timestamptz` 를 `java.sql.Timestamp` 로 준다

`JdbcClient` 의 `query().singleRow()` 는 `Map<String, Object>` 를 돌려주는데, 그 안의 시각 값은
`OffsetDateTime` 이 아니라 `java.sql.Timestamp` 다. 캐스팅하면 `ClassCastException` 이고
**컴파일은 통과해서 실행할 때만 드러난다.**

시각 컬럼을 읽을 때는 RowMapper 로 받는다.

```java
.query((rs, rowNum) -> new Row(rs.getObject("created_at", OffsetDateTime.class)))
```

### `query(레코드.class)` 는 소문자 저장값을 열거형으로 못 읽는다

`JdbcClient` 의 자동 매핑은 `String` → `enum` 을 `Enum.valueOf` 로 한다. 우리 저장값은
**소문자**고 상수 이름은 대문자라(`D5` 「값의 형식」) 그 자리에서 못 찾는다.

**컴파일은 통과한다.** 레코드 컴포넌트를 `String` 에서 열거형으로 바꾸는 순간
조회하는 코드는 그대로인데 **읽을 때만 터진다.**

```java
// 안 한다 — 저장값이 'transactional' 이라 valueOf 가 못 찾는다
.query(Version.class)

// 한다 — of 가 모르는 값에도 그 자리에서 터진다
.query((rs, rowNum) -> new Version(..., NotificationKind.of(rs.getString("kind")), ...))
```

`valueOf` 가 우연히 맞는 자리를 만들어도 안 쓴다 — **모르는 값에 `IllegalArgumentException`
을 던지는 것과 `of` 가 던지는 것은 메시지가 다르고**, 어긋난 것이 마이그레이션인지
요청인지가 안 드러난다(`43a-25`).

### `@Cacheable` 은 private 메서드와 자기 호출에 안 먹는다

프록시가 메서드 호출을 가로채는 방식이라 **같은 객체 안에서 부른 것은 프록시를 안 거친다.**
붙여 놓고 안 걸리는 것을 눈치 못 채는 게 이 함정의 성질이다.

청크 4a 에서 조회를 `PermissionRuleLoader` 로 뺀 이유가 이것이다.
캐시를 새로 붙일 때 **부르는 쪽과 캐시된 메서드가 다른 빈에 있는지** 먼저 본다.

**`@Transactional` 도 같다.** 자기 호출이면 전파 설정이 통째로 무시되고
`REQUIRES_NEW` 가 `REQUIRED` 처럼 돈다 — **고쳤다고 믿는 채로 원래 결함이 남는 모양이라
증상으로는 안 갈린다.** 청크 4b-2 가 삽입을 `AuditLogWriter` 로 뺀 이유다.

### `@BeforeTransaction`·`@AfterTransaction` 은 `@Nested` 클래스에서 안 돈다

Spring 은 그 표시를 **테스트 클래스의 상속 계층**에서 찾는데, 중첩 클래스는 바탕 클래스를
상속하지 않는다. 바탕에 달아 두면 중첩 안 쓴 클래스에서만 돌아서 **일부만 정리된다.**

`@BeforeEach`·`@AfterEach` 는 JUnit 이 바깥 클래스까지 훑으므로 중첩에서도 돈다.
그래서 트랜잭션 밖에서 해야 하는 정리는 **`@BeforeEach` 안에서 `REQUIRES_NEW` 로 연다**
(`PostgresTestBase.purgeCommittedAuditLogs`).

**뒤가 아니라 앞에서 지운다.** 뒤에서 지우면 아직 커밋 안 된 그 테스트의 행을
다른 트랜잭션이 지우려 드는 모양이 돼서 잠금에 걸린다.

### Testcontainers 는 Boot BOM 이 관리하지 않는다

버전을 직접 지정한다. 이유와 Docker 29 함정은 `build.gradle.kts` 주석에 있다.
요약하면 **1.21.4 미만은 Docker 29 에서 안 뜨고, 오류 메시지에 원인이 안 드러난다.**

### Boot BOM 의 Tomcat 이 보안 패치보다 낮을 수 있다

**Boot 를 올려서 닫히는 경보가 아니다.** 2026-09-12 에 `tomcat-embed-core` critical 셋이
열렸을 때 BOM 값이 `11.0.24` 고 패치가 `11.0.25` 였는데, **Boot 는 `4.1.1` 이 이미 최신이라**
올릴 자리가 없었다(`4.1.2`·`4.2.0` 이 `repo1.maven.org` 에서 둘 다 404). Tomcat 은 Boot 와
릴리스 주기가 따로라 **그 사이에 패치만 나온 구간이 생긴다.**

```kotlin
extra["tomcat.version"] = "11.0.25"
```

`io.spring.dependency-management` 가 이 속성을 읽어서 BOM 값을 덮는다. 한 줄이
`tomcat-embed-core`·`-el`·`-websocket` 셋을 다 움직인다 — `gradlew dependencies` 에
`11.0.24 -> 11.0.25` 로 뜬다.

**BOM 이 검증한 조합에서 벗어나는 것이라 되돌릴 조건을 같이 건다.** 되돌려도 되는지는
`TomcatVersionTest` 가 판정한다 — 클래스패스에 실제로 올라온 판을
`ServerInfo.getServerNumber()` 로 읽어서 `11.0.25` 미만이면 빨개진다. **그 줄만 있고
테스트가 없으면 다음 Boot 업그레이드가 조용히 되돌린다** — 지워도 빌드가 초록이다.

### `bootRun` 을 멈춰도 java 프로세스가 남는다

Gradle 태스크를 죽여도 **`bootRun` 이 띄운 자식 java 는 8080 을 계속 쥔다.**
다음 기동은 `Port 8080 was already in use` 로 죽는데, **그때 `curl` 은 200 을 준다** —
낡은 인스턴스가 답하기 때문이다.

**옛 코드로 검증하게 되는 자리다.** 마이그레이션이나 응답 형식을 고친 뒤라면 결과가 거짓이 된다.
포트를 잡은 프로세스를 직접 죽이고 다시 띄운다.

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

**`npm run dev` 도 같다.** 3000 을 쥔 node 가 남고, 다음 `dev` 는 조용히 실패하는데
**낡은 서버가 계속 답한다.** 그쪽은 파일 변경을 따라가므로(HMR) 새 코드가 도는 것처럼 보이지만
**시작 시점에 만들어진 것은 안 바뀐다** — 그 상태에서 `notFound()` 가 500 을 준 적이 있다.
포트 둘 다 위 명령으로 확인하고 시작한다. **화면 축은 두 포트를 다 본다.**

### `JAVA_HOME` 이 JDK 11 을 가리킨다

이 환경의 문제다. `CLAUDE.md` 의 검증 절에 명령이 있다.

### SpotBugs 는 리포트를 안 켜면 파일을 안 남긴다

`spotbugsMain` 이 검출을 **콘솔에는 찍는데** `build/reports/` 에는 아무것도 안 만든다.
플러그인 6.x 의 기본값이 그렇다 — 리포트를 명시적으로 만들어야 한다.

```kotlin
tasks.withType<com.github.spotbugs.snom.SpotBugsTask> {
	reports.create("xml") { required = true }
	reports.create("html") { required = true }
}
```

**증상이 「빌드는 실패하는데 볼 것이 없다」라 원인이 안 드러난다.** 콘솔 출력을 세는 것으로는
유형별 분포를 못 뽑고, 분포를 모르면 무엇을 제외할지 정할 수가 없다.

### ErrorProne 을 안 골랐다

둘 다 버그 패턴 검출기인데 **붙는 자리가 다르다.**

| | SpotBugs | ErrorProne |
|---|---|---|
| 읽는 것 | 바이트코드 | 소스(javac 플러그인) |
| JDK 를 올리면 | 클래스 파일 버전만 맞으면 된다 | **javac 내부 API 를 써서 같이 막힌다** |

이 저장소는 툴체인이 JDK 25 다. ErrorProne 은 새 JDK 마다 `--add-exports` 를 붙여야 하는
구간이 생기고, 그 구간에는 **검출기를 끄는 것 말고 할 수 있는 것이 없다.**
SpotBugs 6.5.11 은 JDK 25 바이트코드에서 그냥 돌았다(청크 `2e` 에서 확인).

**성능이나 검출 폭으로 고른 것이 아니다** — 위 표의 두 번째 줄 하나로 갈렸다.
SpotBugs 의 기본 검출기는 실제로 신호가 얇다(첫 측정 100건 중 96건이 구조적 오탐).
넓히려면 `find-sec-bugs` 를 얹는데 그건 청크 `2e-1` 이 다룬다.

### Windows 에서 만든 실행 파일은 실행 비트가 없다

**로컬에서는 영원히 안 드러난다.** Windows 에 그 개념이 없어서 `./gradlew` 가 잘 돌고,
git 은 `100644`(실행 불가)로 들고 있다. **리눅스 러너에서만 `Permission denied`(exit 126)** 가 난다.

`2026-08-23` 에 CI 가 처음 돌면서 44초 만에 죽은 자리다. 그전까지 `./gradlew build` 를
수십 번 돌렸는데 한 번도 안 나왔다.

```
git ls-files -s backend/gradlew        # 100644 이면 실행 비트가 없다
git update-index --chmod=+x backend/gradlew
```

**새 실행 파일을 저장소에 넣을 때 같이 본다.** 지금 걸릴 만한 것은 `gradlew` 하나뿐이지만,
셸 스크립트를 더하는 청크는 그 자리에서 모드를 확인한다 — **CI 가 잡아 주지만 한 번 빨개진 뒤다.**

### `set_updated_at` 트리거 때문에 시각을 되돌릴 수 없다

파기·만료를 테스트하려면 "오래된 행" 을 만들어야 하는데, `update` 로 `updated_at` 을 과거로 넣으면
**그 트리거가 다시 `now()` 로 덮어쓴다.**

```java
// 안 된다. 트리거가 now() 로 되돌린다
insert ...; update cart set updated_at = :old ...

// 한다. 트리거가 before update 에만 걸려 있다
insert into cart (cart_token, updated_at) values (:t, :old)
```

`created_at` 은 트리거가 없어서 `update` 로도 된다. **`updated_at` 만 이 문제가 있다.**

### `LocalTime.MAX` 를 `timestamptz` 에 넣으면 다음날이 된다

`23:59:59.999999999` 는 나노초까지고 Postgres 는 **마이크로초까지만 담고 나머지를 올린다.**
저장된 값은 다음날 `00:00:00` 이다.

「말일 24시」로 쓸 때는 맞는 값이지만, **그것을 다시 날짜로 되돌리면 말일이 아니라 말일+1** 이다.

```java
lastDay.atTime(LocalTime.MAX)                    // 저장되면 lastDay + 1일 00:00
read().atZoneSameInstant(ZONE).toLocalDate()     // lastDay 가 아니다
```

시각으로 비교하는 코드는 멀쩡하고 **날짜로 되돌리는 코드만 틀린다.** 그래서 늦게 드러난다 —
`OrderStatusServiceTest` 가 말일이 금요일인 날에만 깨졌다(청크 `11-4` 가 고쳤다).

### 텍스트 블록은 줄 끝 공백을 지운다

SQL 을 텍스트 블록으로 쓰다가 변수를 이으면 단어가 붙는다.

```java
// 안 된다. "order by" 뒤 공백이 사라져서 order byp.created_at 이 된다
"""
 order by """ + orderBy

// 한다. 공백을 문자열에 직접 넣는다
"""
 group by p.product_id
"""
+ " order by " + orderBy
```

Java 가 들여쓰기를 계산할 때 각 줄의 **후행 공백을 제거**한다(`\s` 이스케이프로 지킬 수는 있다).
증상이 `syntax error at or near` 라 원인이 안 드러난다 — 소스에는 공백이 보인다.

### Jackson 3 은 빠진 필드를 기본형에 못 넣는다

요청 본문에 없는 필드가 `boolean`·`long`·`int` 로 가면 **요청 전체가 깨진다.**

```
MismatchedInputException: Cannot map `null` into type `boolean`
(set `DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES` to 'false')
```

Jackson 2 는 이 기능이 꺼져 있어서 `false`·`0` 이 들어갔다. **Jackson 3 은 켜져 있다.**
Boot 3 예제를 그대로 옮기면 여기서 걸린다.

증상이 나쁘다. "이 필드가 없다" 가 **"요청 형식이 맞지 않는다" 로 뭉개져서** 어느 칸인지 안 드러나고,
Spring 은 이 예외를 `DEBUG` 로 찍어서 로그에도 안 남는다.

**요청 record 에는 래퍼 타입을 쓴다.** 필수면 `@NotNull` 을 걸어 Bean Validation 이 필드를 짚게 한다.
전역으로 그 기능을 끄면 `null` 이 조용히 `0`·`false` 가 돼서 진짜 실수가 안 드러난다.

```java
// 안 한다
@PositiveOrZero long price

// 한다
@NotNull @PositiveOrZero Long price
```

### `@RestControllerAdvice` 만으로는 프레임워크 예외를 못 잡는다

`spring.mvc.problemdetails.enabled=true` 를 켜면 Spring 이 **자기 핸들러를 먼저 등록한다.**
검증 실패(`MethodArgumentNotValidException`), 깨진 JSON, 지원 안 하는 메서드·미디어 타입이
전부 그쪽으로 간다.

그래서 `@ExceptionHandler(MethodArgumentNotValidException.class)` 를 적어 둬도 **안 불린다.**
증상이 조용하다 — 응답은 나가는데 우리가 넣은 `type` 과 `trace_id` 만 없다.

**`ResponseEntityExceptionHandler` 를 상속하고 메서드를 재정의해야** 우리 형식이 걸린다.

```java
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(...) { }
}
```

`WebRequest` 에서 `HttpServletRequest` 를 꺼내려면 `((ServletWebRequest) request).getRequest()` 다.

### 보안 필터의 401 은 예외 처리기가 못 잡는다

인증 실패는 `AuthenticationEntryPoint` 가 MVC 에 닿기 전에 응답을 끝낸다.
`@RestControllerAdvice` 는 컨트롤러까지 온 요청에만 걸려서 **401 만 본문 없이 나간다.**

본문을 그 자리에서 직접 써야 한다. 우리는 `ProblemEntryPoint` 가 하고,
본문을 만드는 것은 `ProblemFactory` 하나로 모았다 — 두 자리가 각자 만들면 형태가 갈린다.

### `MockMvc` 의 `Content-Type` 은 charset 이 붙는다

`application/problem+json` 을 기대하면 `application/problem+json;charset=UTF-8` 이 와서 어긋난다.
`content().contentTypeCompatibleWith(...)` 로 타입만 본다.

### `ResultActions` 에 `.as()` 가 없다

AssertJ 문법이다. MockMvc 체인에 붙이면 컴파일이 깨진다. 이유는 주석으로 적는다.

### 지연 트리거는 `@Transactional` 테스트에서 한 번도 안 돈다

`deferrable initially deferred` 로 건 제약은 **커밋 시점에** 검사한다.
테스트는 `@Transactional` 이라 롤백하므로 그 시점이 오지 않고, **검사가 한 번도 실행되지 않은 채 전부 초록이 된다.**
트리거를 아무리 틀리게 짜도 테스트가 안 잡는다.

```java
jdbc.sql("set constraints all immediate").update();
```

이 문장이 밀려 있던 검사를 그 자리에서 돌린다. `OrderSchemaTest.flush()` 가 그것이다.
지연 제약을 새로 걸면 **테스트에 이 호출이 있는지부터 본다** — 없으면 검사한 적이 없는 것이다.

트리거가 `raise exception` 으로 떨어뜨리면 SQLSTATE 가 `P0001` 이라
Spring 이 `UncategorizedSQLException` 으로 준다. `DataIntegrityViolationException` 이 아니다 —
둘을 같이 받으려면 `DataAccessException` 으로 잡는다.

### 지연 트리거 안에서 `NEW` 는 커밋 시점의 값이 아니다

**`NEW` 는 그 트리거를 걸어 준 문장 시점의 행이다.** 커밋 때 도는 것이지 커밋 때의 값을 보는 게 아니다.

같은 트랜잭션에서 넣고 고치는 흐름이면 이 차이가 결과를 뒤집는다.

```
insert (컬럼 = null)   ← 트리거 예약. NEW 에 null 이 박힌다
update (컬럼 = 값)     ← 트리거 또 예약. 이건 통과한다
commit                 ← 앞의 예약분이 null 로 터진다
```

**`after insert` 를 같이 걸어 두면 그 트리거는 절대 통과할 수 없다.**

```sql
-- 안 된다: insert 로 걸린 예약분이 언제나 null 을 본다
if new.response_body is null then raise exception ...

-- 한다: 행을 다시 읽는다. 지워졌으면 검사할 것이 없다
select response_body into v_body from idempotency_key
 where idempotency_key_id = new.idempotency_key_id;
if not found then return null; end if;
```

`V16` 의 `assert_order_amounts` 가 처음부터 이 모양이었고, `V17` 은 `NEW` 를 믿었다가
**`POST /api/orders` 가 실서버에서 언제나 500** 이었다. 위의 「한 번도 안 돈다」와 겹쳐서
테스트 30개가 전부 초록인 채로 지나갔다 — 청크 `35c` 가 HTTP 층에서 첫 커밋을 일으켜 잡았다.

### 동의 항목·정책 문서는 「지금 판」을 골라야 한다

개정판을 **미리 넣어 두고 시행 시각에 갈아 끼우는** 설계다(`V27` 의 불변 트리거).
그래서 표에는 **아직 시행 안 된 판이 같이 들어 있다.**

```sql
-- 틀린다. 시행 전인 판을 집는다
where code = :code order by version desc limit 1

-- 맞다
where code = :code and effective_at <= now()
order by effective_at desc, version desc limit 1
```

**같은 실수가 하루에 셋이었다**(`D2-7`·`6b-1`). `V36`(약관 제3판, 시행 이레 뒤)이 들어오는
순간 한꺼번에 드러났다.

| 어디 | 어떻게 됐나 |
|---|---|
| `MeConsentTest` 픽스처 | **시행 전 판에 동의**한 것으로 기록돼서 「내 사본」 테스트가 깨졌다 |
| `V900` 데모 시드 | `join consent_item ci on ci.is_required` — 판을 안 가려서 **두 판에 다 동의**했다 |
| 운영 경로 둘 | **안 틀렸다.** `SignupService.currentConsentItems`·`ConsentService.findItem` 이 둘 다 본다 |

**운영만 맞고 나머지가 틀린 것이 이 함정의 모양이다.** 시행 전 판이 없는 동안에는
`order by version desc` 도 같은 답을 줘서, **개정판을 처음 넣는 날까지 아무도 모른다.**

`version` 이 크다고 지금 판이 아니다 — 고르는 기준은 언제나 `effective_at` 이다.

### 시드를 한 번 넣은 DB 에는 새 마이그레이션이 안 들어간다

데모 시드가 `V900` 이라 **적용 이력의 최고 버전이 900** 이 된다.
그 뒤에 `V19` 같은 낮은 번호를 추가하면 Flyway 가 순서를 어긴 것으로 보고 기동을 막는다.

```
Detected resolved migration not applied to database: 19.
```

`local` 프로필로 한 번 띄운 로컬 DB 에서만 난다. **`docker compose down -v` 로 다시 만든다** —
`out-of-order=true` 를 켜는 쪽은 안 골랐다. 그걸 켜면 진짜 순서 사고도 같이 통과한다.

**`6b-1` 에서 실제로 막혔고, 그때 이 결정을 어겼다가 되돌렸다.** `V29` 까지 적용된 로컬 DB 에
`V30`~`V36` 이 한꺼번에 왔고, `out-of-order` 를 켜 봤더니 **그것만으로도 안 뜬다** —
옮기기 전에 도는 검증이 먼저 막아서 `ignore-migration-patterns` 까지 켜야 했다.
**끄는 스위치가 둘이면 그만큼 안 보이게 된다**는 것이 이 문서의 판단을 되레 뒷받침했다.

**다시 만드는 값이 `6b-1` 로 싸졌다.** 그전에는 상품을 손으로 넣어 둔 것이 같이 날아가서
`down -v` 가 아까웠는데, 이제 시드가 상품·옵션·조합·재고까지 넣는다 —
**날아갈 것이 없으면 다시 만드는 것이 가장 싼 길이다.**

### 422 와 `asText()` 는 이름이 바뀌었다

빌드 경고 16개의 정체다. 둘 다 <b>기능이 없어진 것이 아니라 이름만 바뀐 것</b>이라
경고를 켜기 전에는 무엇이 문제인지 안 드러났다.

| 옛 이름 | 지금 | 왜 |
|---|---|---|
| `HttpStatus.UNPROCESSABLE_ENTITY` | `UNPROCESSABLE_CONTENT` | RFC 9110 이 422 를 "Unprocessable Content" 로 고쳤다 |
| `status().isUnprocessableEntity()` | `isUnprocessableContent()` | 같은 이유 |
| `JsonNode.asText()` | `asString()` | Jackson 3 |

`-Xlint:deprecation` 을 켜 두는 이유가 이것이다(`build.gradle.kts`).
켜기 전에는 "deprecated API 를 쓴다" 까지만 나오고 **어느 줄인지 안 나온다.**

### `set_config(..., true)` 로 켠 값은 서브트랜잭션이 abort 되면 되돌아간다

`V41` 의 재고 차단이 이 사실 위에 서 있다. `move_stock()` 이 `shop.stock_move` 를 켜고
`UPDATE` 뒤에 끄는데, **그 사이에서 예외가 나면 켠 채로 남는 것처럼 읽힌다.**
남으면 같은 트랜잭션에서 `sku_stock.on_hand` 직접 `UPDATE` 가 열려서 차단이 뚫린다.

**안 뚫린다.** `is_local := true` 로 켠 값은 GUC 스택에 서브트랜잭션 단위로 쌓여서,
그 구간이 abort 되면 켜기 전 값으로 돌아간다. 그리고 **예외를 삼키려면 abort 가 반드시 먼저 온다** —
Postgres 는 오류 뒤에 세이브포인트로 되돌리지 않으면 그 트랜잭션에서 아무것도 못 한다.

두 갈래로 확인했다.

| 삼키는 방식 | 삼킨 뒤 플래그 | 직접 `UPDATE` |
|---|---|---|
| `rollback to savepoint` — Spring 중첩 트랜잭션이 이것이다 | 빈 값 | 거부 |
| plpgsql `exception when others` | 빈 값 | 거부 |

**그래서 `move_stock()` 에 `exception` 블록을 안 넣었다**(청크 `53a`). 넣으면 막는 것 없이
암묵 세이브포인트가 재고 이동마다 하나씩 생긴다 — plpgsql 의 `exception` 블록이 그것이다.
`SkuStockMovementTest` 의 「막는 것」이 두 갈래를 다 밟아서, 이 사실이 바뀌면 거기서 빨개진다.

### Postgres 의 데드락은 `DeadlockLoserDataAccessException` 이 아니다

`40P01` 을 그 이름의 예외로 받을 것 같지만 **`PessimisticLockingFailureException`** 으로 온다.
Spring 이 오류 코드표(`sql-error-codes.xml`)로 번역할 때만 세분화하고,
Postgres 는 SQLSTATE 앞 두 자리(`40` = 트랜잭션 롤백)로 번역돼서 상위 타입에 멈춘다.

**재시도를 붙일 때 예외 이름으로 잡으면 데드락을 놓친다**(`D11` 「재시도」는 `40001`·`40P01` 둘 다 잡으라고 정했다).
SQLSTATE 를 직접 보는 쪽이 확실하다 — `OrderConcurrencyTest` 가 그렇게 확인한다.

### 롤백을 끈 테스트는 정리도 한 트랜잭션이어야 한다

`@Transactional(propagation = NOT_SUPPORTED)` 로 롤백을 끄면 정리 SQL 도 **문장마다 커밋된다.**
그러면 지연 트리거가 중간 상태를 본다 — `order_item` 만 지운 순간
`항목이 없는 셀러 주문` 으로 정리가 통째로 실패한다.

정리를 `TransactionTemplate` 하나로 묶어서 다 지운 뒤에 검사가 돌게 한다.
### 지연 제약 트리거는 롤백하는 테스트에서 한 번도 안 돈다

`deferrable initially deferred` 는 **커밋 시점에** 검사한다. Spring 테스트는 기본이 롤백이라
커밋이 없고, 그래서 그 트리거가 도는 순간이 오지 않는다.

`V63` 이 그것을 드러냈다. `seller_order_return_status_check`(반품 행 없이 묶음만 반품 상태로
옮기는 것을 막는다)를 넣고 빌드를 돌렸는데, **막힐 것이라 예상한 기존 픽스처 다섯이 전부 통과**했다.
운영 경로는 커밋하므로 막힌다 — **테스트만 그것을 못 본다.**

검사하려면 트랜잭션 안에서 `set constraints all immediate` 를 부른다. 그 자리에서 예외가 난다.

```java
jdbc.sql("set constraints all immediate").update();
```

같은 성질인 것이 이미 넷 더 있다 — `refund_requires_rejection_reason`(`V48`),
`shop_order_amounts_check`·`seller_order_amounts_check`·`order_item_amounts_check`(`V16`),
`idempotency_key_response_check`(`V17`), `refund_amounts_check`(`V23`).
**「초록이니까 그 제약이 돈다」가 이 넷에는 성립하지 않는다.**


### 한글이 든 본문을 `curl -d` 로 보내면 400 이 난다

이 환경의 Git Bash 가 명령줄 인자를 UTF-8 로 안 넘겨서 서버가 못 읽는다.
증상이 **「요청 형식이 맞지 않는다」 하나뿐**이라 필드가 틀린 것처럼 보인다 —
같은 요청을 영문 이름으로 보내면 201 이 나와서 원인이 인코딩인 줄 모른다.

```bash
# 안 된다
curl -d '{"name":"기본 반팔 티셔츠", ...}'

# 된다 - 파일로 두고 바이트 그대로 보낸다
curl -H "Content-Type: application/json; charset=utf-8" --data-binary @body.json
```

파일은 Write 도구로 만든다. 셸 heredoc 도 같은 자리에서 깨진다.

### `next/image` 는 목록에 없는 호스트를 통째로 거부한다

`next.config.ts` 의 `images.remotePatterns` 에 없으면 그 자리에서 막힌다.
막아 두는 것이 기본값인 이유는, 아무 주소나 받으면 **우리 서버가 남의 이미지를
대신 내려받아 주는 통로**가 되기 때문이다.

상품 사진 자리표시(`picsum.photos`)를 청크 `14` 가 처음 등록했다.
진짜 업로드(청크 26)로 바뀔 때 이 항목도 같이 바뀐다.

**시작할 때도 한 번 지운다.** 정리가 한 번 실패하면 그 데이터가 컨테이너에 남고,
같은 실행의 **뒤 테스트가 이메일 유니크에 걸려 시작도 못 한다** — 컨테이너와 스키마는
JVM 당 하나라 앞 테스트의 잔여물을 그대로 물려받는다.

### Redis 는 테스트 롤백이 안 되돌린다

`PostgresTestBase` 가 `@Transactional` 이라 DB 는 테스트마다 깨끗하게 시작하는데,
**Redis 에 쓴 것은 그대로 남는다.** 같은 키를 쓰는 테스트가 여럿이면 앞 테스트의 값이 뒤에 새어 간다.

`AuthLoginTest` 가 그 자리다 — 일부러 로그인을 실패시키는 테스트가 많고 전부 같은 (계정, IP) 라,
안 지우면 **앞 테스트가 쌓아 둔 실패 때문에 뒤 테스트가 차단된 채 시작한다.**

Redis 를 쓰는 테스트는 `@BeforeEach` 에서 자기 키를 지운다.

### Redis 의존성을 넣어도 캐시가 자동으로 안 넘어간다

`spring-boot-starter-data-redis` 를 넣으면 Spring Boot 가 `RedisCacheManager` 를 자동설정할 수 있다.
그러면 **판정 캐시(청크 4a)가 아무도 모르게 Redis 로 옮겨 간다** — 이관은 청크 39 의 일이다.

여기서는 안 넘어간다. `PermissionCacheConfig` 가 `CacheManager` 빈을 **명시적으로** 선언했고
자동설정이 `@ConditionalOnMissingBean` 이라 뜨지 않기 때문이다.

**이건 우연히 성립한 안전장치라 테스트로 고정해 뒀다**(`RedisConnectionTest`).
누가 그 빈을 지우면 캐시 구현이 조용히 바뀌는데, 그건 코드 어디에도 안 보인다.

### `.next/types` 는 빌드 산출물인데 `tsc` 가 그것을 읽는다

`LayoutProps`·`PageProps` 는 소스에 없다 — `next build`·`next dev`·`next typegen` 이 `.next/types` 에
만들고 `tsconfig.json` 이 그 디렉터리를 `include` 에 둔다. 그래서 둘이 엇갈린다.

| 한 일 | 무슨 일이 나나 | 푸는 법 |
|---|---|---|
| 탐침 페이지를 넣고 `next build` 를 돌렸는데 **중간에 죽었다** | `.next/types/validator.ts` 가 그 페이지를 가리킨 채 남아 `tsc --noEmit` 이 `Cannot find module '../../src/app/<탐침>/page.js'` 로 빨갛다 | `rm -rf .next` 뒤 아래 줄 |
| `.next` 를 통째로 지웠다 | `layout.tsx` 가 `LayoutProps` 를 못 찾는다 | `npx next typegen`(10초). `next build` 를 다시 안 돌려도 된다 |

**빠른 레인이 `tsc` 를 돌리므로**(`verify.sh`) 탐침을 넣었다 뺀 청크는 이것을 밟는다 — `Q29` 가 둘 다 밟았다.

### 화면을 진짜 브라우저로 밟는 방법이 있다

`curl` 은 HTML 만 받는다. **버튼을 눌러 무슨 일이 나는지는 못 본다** — 로그인이 200 인데
화면이 안 넘어가는 결함(`13-2` 수정)이 그 사이에 숨어 있었다.

Puppeteer 가 이 기계에 깔려 있다. 저장소 의존성이 아니라 **사용자 홈**에 있다.

```
C:\Users\EJG\node_modules\puppeteer   (25.3.0)
~/.cache/puppeteer                    (Chrome 바이너리)
```

**저장소에 안 넣는다.** 화면 테스트를 정식으로 세우는 것은 나중 청크고(`D15`),
지금은 손으로 확인할 때만 쓴다. 스크래치패드에 스크립트를 두고 부른다.

```js
// .mjs 여야 한다. 이 판은 ESM 전용이라 require 가 안 된다.
const { default: puppeteer } = await import(
  "file:///C:/Users/EJG/node_modules/puppeteer/lib/puppeteer/puppeteer.js"
);
```

**경로를 `file://` URL 로 준다.** Git Bash 의 POSIX 경로(`/c/Users/...`)를 그대로 넘기면
Node 가 `C:\c\Users\...` 로 읽어서 못 찾는다.

### 브라우저로 밟을 때 헛것을 세는 자리가 셋이다

`점검 D-2` 가 1차에서 **지적 50건을 냈는데 전부 오탐**이었다. 재는 법이 틀렸던 것이라 적어 둔다.

| 헛것 | 왜 | 어떻게 |
|---|---|---|
| **이름 없는 `<input>`** | `aria-label`·`textContent` 만 읽으면 `<label for>` 로 이어진 이름을 못 본다 | `page.accessibility.snapshot()` 으로 **브라우저에게 묻는다** |
| **`<nextjs-portal>`** | Next.js **개발 오버레이**다(shadow root). 초점 대상으로 세면 「크기 0」·「초점 역행」이 화면마다 뜬다 | `e.closest("nextjs-portal")` 로 걷어낸다 |
| **로그인이 안 된다** | 개발 빌드가 `TEST_ACCOUNT` 로 칸을 미리 채운다(`login-form.tsx`) — 그냥 치면 값이 두 벌이 된다 | `type` 전에 `input.value = ""` |

**셋 다 「화면이 틀린 것」이 아니라 「재는 쪽이 틀린 것」이다.** `/inspection` 의
「코드를 안 읽고 요건표만 보고 판정하지 않는다」가 브라우저 쪽에서도 그대로 걸린다 —
**판정을 뒤집기 전에 재는 도구부터 의심한다.**

### `notFound()` 는 404 가 아니라 200 으로 나간다

화면 안에서 `notFound()` 를 부르면 **없는 쪽 UI 는 그려지는데 HTTP 상태는 200** 이다.
라우팅 단계에서 안 잡힌 주소(`/아무데나`)만 진짜 404 다.

```
/아무데나없는주소                 404   라우팅이 잡는다
/products/999999                  200   notFound() 가 던진다
/seller/orders/S-99999999-XXXXXX  200   같다
```

**우리가 잘못 쓴 것이 아니다.** 번들된 문서(`node_modules/next/dist/docs`)의
`04-functions/not-found.md` 가 그렇게 적어 뒀다 — 검사가 `<Suspense>` 안에서 도는데
**응답은 이미 200 으로 흘러나가기 시작했고, 스트리밍이 시작된 뒤에는 상태를 못 바꾼다.**
대신 Next 가 `<meta name="robots" content="noindex">` 를 넣어 색인에서 뺀다.

진짜 404 를 내려면 **스트리밍 전에** 검사해야 하고, 그 자리는 `proxy` 다.

**틀린 주석이 하나 있었다**(`13g` 에서 고쳤다). `orders/[orderNumber]/page.tsx` 가
「화면만 바꿔 그리고 200 으로 답하지 않는다」고 적어 뒀는데 **실제로는 200 이었다** —
확인 없이 쓴 문장이 코드 옆에서 사실처럼 읽히던 자리다.

### `bootRun` 을 죽여도 8080 은 안 풀린다

`bootRun` 은 앱을 **자식 JVM** 으로 띄운다. Gradle 쪽 프로세스를 죽이면 그 자식은 남고,
포트를 쥔 채라 다음 기동이 이렇게 끝난다.

```
APPLICATION FAILED TO START
Web server failed to start. Port 8080 was already in use.
```

**Gradle 이 안 죽였다는 신호가 없다.** 죽인 명령은 성공으로 끝나고, 실패는 다음 기동에서
전혀 다른 얼굴로 나온다 — 고친 코드가 원인처럼 보인다.

포트를 쥔 것을 직접 찾아 내린다.

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen |
    Select-Object -ExpandProperty OwningProcess -Unique |
    ForEach-Object { Stop-Process -Id $_ -Force }
```

### Dependabot 경보는 가지에 밀어도 안 닫힌다

의존성을 올리고 밀었는데 경보가 그대로면 **고친 것이 안 먹은 것처럼 보인다.**

**의존 그래프가 기본 가지 기준이라 그렇다.** `dependency-submission.yml` 이 `main` push 에서만
도는데, 그건 **작업 가지의 좌표가 그래프에 남지 않게 하려는 의도**다(`2e-3`).

```
가지에 push  ──▶ CI 는 돈다.  그래프는 그대로 ──▶ 경보도 그대로
main 에 머지 ──▶ 그래프 갱신              ──▶ 경보가 fixed 로 닫힌다
```

2026-09-12 에 `2e-6` 이 tomcat 을 `11.0.25` 로 올리고 밀었는데 critical 셋이 안 닫혔고,
**머지 직후 셋이 한꺼번에 `fixed`** 가 됐다. **오탐(`commons-lang3`)은 이 경로와 무관하다** —
그건 사람이 `inaccurate` 로 닫는 것이고 `gh api -X PATCH .../dependabot/alerts/N` 이 그 명령이다.

### `@Size` 는 record component 에 안 남는다

리플렉션으로 요청 record 의 검증 규칙을 읽을 때 걸린다(`Q22`).

```java
component.getAnnotation(Size.class)   // null 이다
```

`jakarta.validation.constraints.Size` 의 `@Target` 에 **`RECORD_COMPONENT` 가 없어서**
컴파일러가 그 애너테이션을 필드로 보낸다. 칸에는 아무것도 안 남는다.

**증상이 「규칙이 없다」로 보인다.** `LengthConstraintTest` 를 처음 돌렸을 때 열한 칸 중
**열이 「@Size 가 없다」로 빨갰고**, 유일하게 통과한 것이 `@EmailAddress` 였다 —
그건 우리가 만든 애너테이션이라 `@Target` 에 `RECORD_COMPONENT` 를 넣어 뒀다.
**남의 애너테이션과 우리 애너테이션이 다르게 동작한 것**이라 원인이 더 안 보인다.

칸과 **그 칸이 만든 필드**를 같이 본다.

```java
component.getDeclaringRecord().getDeclaredField(component.getName()).getAnnotations()
```

**메타 애너테이션도 같이 본다.** 규칙을 하나로 모으면(`@Password`·`@EmailAddress`)
`@Size` 가 그 안에 들어가므로, 직접 붙은 것만 훑으면 모은 칸이 통째로 빠진다.

### 시드를 한 번 넣은 로컬 DB 는 다음 마이그레이션에서 기동을 막는다

`local` 프로필의 시드가 `V900`·`V901`·`V902` 라 **번호가 실제 마이그레이션보다 위**다.
그 DB 에 `V64` 를 더하면 Flyway 가 순서를 어긴 것으로 보고 기동 전에 멈춘다.

```
Detected resolved migration not applied to database: 64.
```

**코드 문제처럼 보인다.** 실패가 마이그레이션 내용이 아니라 번호 배치에서 나오는데,
메시지는 그 파일 번호만 말한다 — 방금 쓴 SQL 을 의심하며 시간을 쓰게 된다.

새 마이그레이션을 손으로 확인할 때는 **빈 데이터베이스에 처음부터 올린다.**
쓰던 것을 지우지 않아도 되고, 시드까지 한 번에 밟히므로 지연 트리거도 같이 돈다.

```bash
docker exec shop-db psql -U shop -d postgres -c "drop database if exists shop_check;" \
                                              -c "create database shop_check owner shop;"
POSTGRES_DB=shop_check ./gradlew bootRun --args='--spring.profiles.active=local'

# 확인이 끝나면 거둔다. 8080 은 bootRun 을 죽여도 안 풀린다(위 절).
docker exec shop-db psql -U shop -d postgres -c "drop database shop_check;"
```

`applied_migrations` 는 **마이그레이션 파일 수 + 시드 3** 이다(`43a-2` 기준 61+3=64).

**지우는 줄이 뒤늦게 붙었다**(2026-09-12). 그전에는 만드는 줄만 있어서 **하루에 두 번 빠뜨렸고**
`shop_spec`·`shop_spec2` 가 남았다 — 확인용 DB 는 **쓰고 나면 티가 안 나서** 다음에 `\l` 을
칠 때까지 아무도 모른다. **이름을 매번 새로 짓는 것이 그 원인이었다**(`shop_q22`·`shop_spec`…) —
`shop_check` 하나로 고정하고 **만들기 전에 지우면** 남아도 다음 확인이 덮는다.

### 컨테이너 재사용은 코드가 아니라 로컬 파일이 켠다

`.withReuse(true)` 가 코드에 있어도 **그것만으로는 안 돈다.** 기계마다
`~/.testcontainers.properties` 에 `testcontainers.reuse.enable=true` 가 있어야 한다.

**안 켜져 있어도 실패하지 않는다.** 경고 한 줄이 나가고 컨테이너를 새로 띄운다.

```
WARN tc.postgres:17-alpine : Reuse was requested but the environment does not support the reuse of containers
To enable reuse of containers, you must set 'testcontainers.reuse.enable=true' in a file located at C:\Users\...\.testcontainers.properties
```

**CI 러너에서는 효과가 없다** — 매번 새 기계라 재사용할 컨테이너가 없다. 로컬 되먹임 전용이다.
켠 뒤에는 `docker ps` 에 이름 없는 컨테이너 둘이 남아 있는 것이 정상이다.

### `@ServiceConnection` 컨테이너는 Spring 컨텍스트마다 뜬다

`Containers` 가 `@TestConfiguration` 이라 **컨텍스트가 갈리면 컨테이너도 따로 뜬다.**
`PostgresTestBase`(MOCK)와 `HttpTestBase`(RANDOM_PORT)가 다른 컨텍스트라
느린 레인 한 번에 postgres 가 셋 뜬다(`--info` 의 `Container postgres:17-alpine started` 를 센다).

재사용을 켜면 그 셋이 같은 컨테이너에 붙어서 기동 비용이 사라진다.

**재사용 컨테이너는 fork 사이에서 안 갈린다**(`2i-2`). 컨테이너가 하나라 fork 넷이 같은 Postgres DB,
같은 Redis 논리 DB 를 잡는다 — 롤백에 안 쓸리는 정리 코드가 남의 fork 를 지운다.
`PostgresTestBase` 가 fork 마다 DB 를 만들어 그것을 가른다. **`@ServiceConnection` 을 쓰면 못 가른다** —
그 표시가 컨테이너의 기본 DB 로 연결을 고정해서, `JdbcConnectionDetails`·`DataRedisConnectionDetails` 를 직접 만든다.

**Redis 논리 DB 는 기본이 16개다.** Gradle 의 `org.gradle.test.worker` 는 빌드 내내 커지는 번호라
그대로 쓰면 넘친다. 컨테이너를 `redis-server --databases 256` 으로 띄워서 받는다 —
**번호를 접으면 안 된다**: 중간이 비는 번호(1, 2, 3, 5)를 fork 수로 접으면 1, 2, 3, 1 로 겹친다.
**대신 격리도 같이 사라진다** — fork 를 늘리면 롤백 안 하는 테스트가 서로를 밟는다(`D15`).

### `initdb.d` 는 볼륨이 비었을 때만 돈다

`docker-compose.yml` 의 `/docker-entrypoint-initdb.d` 마운트는 **데이터 디렉터리가 비어 있을 때 한 번**만 실행된다.
이미 `db-data` 가 있는 기계에서는 파일을 넣어도 **아무 일이 안 일어나고 오류도 안 난다.**

```
docker compose down -v && docker compose up -d --wait
```

**`-v` 가 핵심이다.** 그냥 `down` 하면 볼륨이 남아서 다음 기동에도 안 돈다.
지우고 올리면 마이그레이션이 처음부터 다시 적용된다 — 로컬 데이터가 사라지는 것이 정상이다.

**Testcontainers 는 이 경로를 안 태운다.** 테스트 DB 는 그대로라, 여기서 켠 확장은 테스트에 안 보인다.

### `shared_preload_libraries` 는 기동 인자라야 먹는다

`pg_stat_statements` 는 `create extension` 만으로는 안 된다. 확장은 만들어지는데
**뷰를 읽는 순간 「must be loaded via shared_preload_libraries」로 터진다.**

그래서 `docker-compose.yml` 의 `db` 에 `command` 로 준다(`42-0`).
`postgresql.conf` 를 따로 두지 않은 것은 **파일이 하나 더 늘고 이미지 기본값과 갈리기 쉬워서**다.

### `claude-code-action` 은 Bash 를 기본으로 안 준다

프롬프트로 `gh pr comment` 를 시켜도 안 돈다. 공식 문서가 그렇게 적었다 —
「By default, Claude cannot execute Bash commands unless explicitly allowed」.
열려면 `claude_args` 에 `--allowedTools "Bash(gh pr comment:*)"` 처럼 명령마다 적는다.

**거부는 실패로 안 보인다.** 잡은 초록이고 `is_error: false` 다.
신호는 결과 JSON 의 **`permission_denials_count`** 하나뿐이고, 무엇이 거부됐는지는
`show_full_output: true` 를 켜야 나온다. 그 값이 12~22 인 채로 PR 여섯이 지나갔다.

### 액션은 `CLAUDE.md`·`.claude` 를 `origin/main` 에서 되살린다

로그에 「Restoring .claude, .mcp.json, .claude.json, ..., CLAUDE.md from origin/main (PR head is untrusted)」
가 찍힌다. PR 이 들고 온 규칙 파일을 안 믿는다는 뜻이라 보안상 맞는 동작이다.

**그래서 규칙을 고친 PR 은 옛 규칙으로 검토된다.** `CLAUDE.md` 나 `doc/reference/*` 를
바꾸는 묶음에서 리뷰가 「문서가 부르는 것이 실물과 맞나」를 물을 때, 리뷰가 읽는 규칙은
그 PR 의 판이 아니라 `main` 의 판이다. 그 PR 의 지적을 읽을 때 이 차이를 먼저 본다.

### 리뷰 한 번이 약 $1.1 에 5분이다

`total_cost_usd` 1.1158 · `duration_ms` 315118 · `num_turns` 47 이 실측값이다(PR #26).
CI 중 제일 길고 제일 비싸다. **PR 을 마무리 때만 여는 근거가 이 수치다**(`2g-4`) —
청크마다 열면 이 값이 청크 수만큼 곱해지는데, 그렇게 연 PR 열하나에서 지적이 0개였다.

### 훅이 산문을 명령으로 읽는 자리가 둘이다

**① 입력이 JSON 이다.** 훅에 들어오는 것은 명령 문자열이 아니라 `{"tool_input":{"command":"…"}}` 라,
`grep` 을 원문에 걸면 **파일 경로·설명문·앞선 필드까지 같이 읽힌다.** 명령만 꺼내고 본다 —
`node -e` 한 줄로 `tool_input.command` 를 뽑는 구절이 훅 넷에 같은 모양으로 들어 있다(`2x`·`2x-1`).

**② 꺼낸 뒤에도 산문이 남는다.** 커밋 메시지가 인자로 실리므로 `git commit -m "sed -i 로 PLAN.md 를
고치던 것을 걷었다"` 의 `sed -i` 와 `PLAN.md` 는 **명령 문자열 안에 진짜로 있다.** 파싱으로는 안 걷힌다.

**그래서 명령 위치에 앵커를 건다** — `(^|[;&|])[[:space:]]*`. 줄머리나 `;`·`&`·`|` 뒤에 붙은 것만
명령으로 본다. `2x-1` 이 문서 린트 훅에서 그것을 실측했다: 앵커 전에는 산문 케이스도 린트를 돌렸고
앵커 뒤에는 진짜 편집에서만 돈다.

**`JAVA_HOME` 훅은 다른 길을 썼다** — 한글이 든 줄을 `perl` 로 통째로 걷는다. 산문이 한국어라
통하는 수고, **한글이 든 진짜 명령은 놓친다.** 새 훅은 앵커 쪽을 쓴다.

### 값 목록을 `support` 로 옮기는 기준은 「소비자가 늘 때 무엇이 자라나」다

`ActorType` 이 `order` 에서 `support` 로 옮겨 간 자리다(`43a-17`, 사용자 선택).
**세 안을 재서 골랐다.**

| 안 | 지금 비용 | 소비자가 늘 때 |
|---|---|---|
| `order` 에 두고 `public` | 한 줄 | **`X → order` 의존이 는다** — `ArchitectureTest` 의 순환 예외가 자란다 |
| **`support` 로 옮긴다** | Javadoc 다시 쓰기 | **`X → support`** — 이미 허용된 방향이다 |
| 자원마다 따로 만든다 | 0 | 같은 목록이 여러 벌 — `43a-*` 사슬이 없애려던 모양 |

**가르는 물음은 「공유가 비용이 되는 시점이 왔나」다.** 쪼개진 서비스에서는 셋째가 맞다 —
공유 라이브러리를 고치면 서비스 여럿을 같이 배포해야 하고, 한 팀이 값을 늘리려는데 다른 팀이
반대하면 둘 다 못 움직인다. **이 저장소는 아직 한 덩어리라 둘째가 맞고**, 쪼갤 때 `support` 의
타입을 각 서비스로 복사하면 셋째로 간다. **둘째 → 셋째는 쉽고 첫째 → 무엇이든은 어렵다.**

**`support` 가 커지는 것을 경계한다.** 거기 들어갈 것은 **작고 거의 안 바뀌는 것**뿐이다 —
자원 개념이 하나씩 새기 시작하면 공용이 아니라 God 모듈이 된다.
`ArchitectureTest` 의 「`support` 는 자원을 모른다」가 그 그물이다.

### 필수 검사는 이름으로 붙지 이벤트를 안 가린다

가지 보호의 필수 검사는 `{context, app_id}` 로 맞춘다 — `gh api repos/…/branches/main/protection` 이
`{"app_id": 15368, "context": "backend"}` 꼴로 낸다(15368 이 GitHub Actions). **어느 이벤트가 그 결과를
만들었는지는 안 본다** — 같은 커밋에 같은 이름으로 붙기만 하면 된다.

**그래서 `push` 와 `pull_request` 를 둘 다 걸면 같은 커밋을 두 번 검사한다**(`2c-3`).
PR #37 의 커밋 하나에 `ci.yml` 2건·`codeql.yml` 2건이 실측됐다. `concurrency` 가 못 막는다:
그룹 키가 `github.ref` 인데 push 는 `refs/heads/…`, PR 은 `refs/pull/N/merge` 라 **다른 그룹**이다.

**`pull_request:` 를 빼도 필수 검사는 찬다** — push 실행이 같은 이름을 붙인다.
**다만 fork 에서 온 PR 은 base 저장소에 push 가 안 돈다.** 바깥에서 PR 을 받기 시작하면
그 줄을 되돌려야 한다. 이 저장소는 지금 fork 가 없다.

**필수 검사 넷이 전부 `ci.yml` 에 있다**(`backend`·`frontend`·`secrets`·`docs`) — 그 파일의
트리거를 건드릴 때는 이 문단을 같이 본다.

### hook `matcher` 는 터미널이 아니라 도구 이름이다

`"matcher": "Bash"` 는 **Claude Code 의 `Bash` 도구**에만 걸린다. 같은 셸 명령을
`PowerShell` 도구로 보내면 그 훅은 안 돈다 — 어느 터미널에서 CLI 를 띄웠는지와는 무관하다.

**2026-09-11 에 한 세션에서 두 번 샜다**(`2x-2`). `git merge` 가 막혀 PowerShell 로 우회한 뒤
`git push` 와 `gh pr merge` 가 그 도구로 나갔고, **push 검사(`2z-2` 도장)와 PR 순서 검사(`2g-1`)가
둘 다 안 돌았다.** 결과는 멀쩡했지만 그건 손으로 같은 확인을 했기 때문이고 게이트가 판정한 것이 아니다.

**세울 때 도구를 하나만 적으면 그 게이트는 반쪽이다.** matcher 는 `Bash|PowerShell` 로 적고,
명령 패턴도 양쪽 도구의 어휘를 같이 든다 — `sed -i`·`tee` 옆에 `Set-Content`·`Out-File` 이 서야
문서 린트가 PowerShell 편집에도 걸린다.

### `main` 가지 보호는 admin 을 기본으로 안 막는다

`required_pull_request_reviews` 를 켜도 `enforce_admins` 가 `false` 면 **저장소 주인은 그대로 민다.**
GitHub 기본값이다. `gh api repos/<소유자>/<이름>/branches/main/protection` 로 읽고,
`-X POST .../protection/enforce_admins` 로 켠다(끄는 것은 `-X DELETE`).

**`false` 인 동안 `main` 을 지킨 것은 로컬 훅 하나였다**(`2x`) — Claude Code 밖에서 민 커밋은
PR 을 안 거치니 CI·AI 리뷰·마무리 대조를 **전부** 건너뛴다. 2026-09-11 에 `true` 로 올렸다(`2x-2`).

### find-sec-bugs 의 SQL 검출기는 `JdbcClient` 를 모른다

`spotbugsPlugins("com.h3xstream.findsecbugs:findsecbugs-plugin:1.14.0")` 는 SpotBugs 4.10.4 에서
**돈다**(`2e-1`) — 얹자마자 `UNSAFE_HASH_EQUALS` 둘이 나왔고 그건 핵심 SpotBugs 에 없는 검출기다.

**다만 SQL 주입은 안 본다.** `JdbcTemplate`·`PreparedStatement`·Hibernate 는 알지만
**`JdbcClient`**(Spring 6.1+ fluent API)를 모른다. 이 저장소의 데이터 접근이 전부 그것이다.

**부순 증거**(2026-09-11): `InquiryQuery` 에 진짜 오염 경로를 심었는데 안 잡혔다 —
`find("i.question like '%" + keyword + "%'", …)`, `keyword` 는 `public` 메서드 인자다.
되돌렸다.

**CodeQL 도 못 본다 — 재 봤다**(`2e-4`, probe 다섯 판). **CodeQL 자체는 멀쩡하다**:
같은 실행에서 `JdbcTemplate` 주입을 `java/sql-injection` HIGH 로 잡고 `JdbcClient` 주입은 안 잡는다.
**`build-mode` 는 상관없다** — `none` 에서도 `JdbcTemplate` 를 잡는다. `SqlTainted.qlx` 는 기본
묶음(80개)에 들어 있고 실제로 돈다.

**`JdbcClient` 가 CodeQL 의 SQL 싱크 모델에 없다.** 검출기 둘이 같은 이유로 눈을 감는다 —
이 API 가 Spring 6.1(2023-11)에 들어왔고 모델이 안 따라왔다.

**그래서 SQL 조립을 자동으로 보는 눈이 지금 없다.** 사람이 보는 자리는 `D23` 「SQL」과 리뷰뿐이다.
**조립 자리는 하나다**(2026-09-11): `InquiryQuery.find` 의 `condition`. `jdbc.sql(` 에 변수가
직접 드는 곳은 0건이고 나머지 `.formatted` 는 전부 예외 메시지다.

**probe 를 다시 칠 때 주의**: 오염원(`@RequestParam`)에서 싱크까지 **경로가 실제로 이어져야 한다.**
`public` 메서드 인자만으로는 안 잡힌다 — `java/sql-injection` 은 원격 오염원에서 출발하는 흐름을 찾는다.
2·3회차를 그것 때문에 버렸다.

**`2e-5` 가 풀었다** — 싱크 목록에 `JdbcClient.sql` 을 더하니 같은 probe 가 **2건 HIGH** 로 잡혔다.
두 `jdbc.sql(...)` 호출을 각각 짚어서, 오염이 `find` 를 지나 실행 지점까지 닿는 것을 다 추적했다.

### CodeQL 싱크 목록은 늘릴 수 있다 — 질의를 복사하지 않는다

`.github/codeql/extensions/projectshop-java/` 가 그 팩이다(`2e-5`). `qlpack.yml` 이
`extensionTargets: codeql/java-all` 로 붙고, `models/*.model.yml` 이 `sinkModel` 에 행을 더한다.
`codeql.yml` 의 `init` 에 `config: packs: java: - ./경로` 로 건다 — **상대 경로가 먹는다.**
따로 게시할 필요가 없다.

**모델 한 줄의 칸**: 패키지 · 타입 · 하위타입까지 · 메서드 · 시그니처 · 확장 · 어느 인자 · 종류 · 출처.
`JdbcClient` 는 인터페이스라 **하위타입 칸이 `true` 여야 한다** — 실제 객체가 구현체다.

**질의를 복사하는 대신 목록을 늘린 이유**: 사본을 두면 원본이 좋아질 때 우리 것만 낡는다.
목록은 CodeQL 이 올라가도 그대로 얹힌다.

**모양을 보는 것이 아니라 오염을 본다.** `InquiryQuery.find` 의 `.formatted(condition)` 이
안 잡히는 것은 결함이 아니다 — `private` 이고 호출자 둘 다 파일 안 리터럴이라 바깥 값이 없다.

### `vitest-axe` 를 안 쓰고 `axe-core` 를 직접 쓴다

`vitest-axe` 는 **정식 판이 없다** — 최신이 `1.0.0-pre.5` 고 2025-01 이후 안 움직인다.
`@vitest/pretty-format` 을 **한 메이저 뒤진 것**(`^3`)으로 물어서, 설치하면 그 패키지가 두 벌 깔리고
**매처 타입이 vitest 4 에 안 붙는다**(`toHaveNoViolations does not exist on type Assertion`).
Dependabot 이 vitest 5 를 이미 올려 두고 있어서 다음 범프에 깨질 자리였다.

**axe 본체는 살아 있다** — `axe-core` 4.13.0(2026-09-10). 그 위의 열 줄은 우리 것이다:
`frontend/src/test/axe.ts` 의 `expectNoAxeViolations` 가 `axe.run` 을 부르고 어긴 마디와
고치는 법을 붙여 던진다.

**axe 가 잎사귀 컴포넌트에서 도는 비율은 8~14% 다**(`Q21` 측정). 무엇을 못 잡는지는
`testing-strategy.md` 「axe 가 무엇을 잡고 무엇을 못 잡나」가 든다.

## 데이터 접근은 `JdbcClient` 다

**JPA 를 안 쓴다**(`Q15` 에서 확정했다). `spring-boot-starter-jdbc` 만 들이고
엔티티는 하나도 안 만든다.

권한 판정이 재귀적인 조인이라 SQL 을 직접 쓰는 편이 읽기 쉬웠고, 그 뒤로 상품·주문·결제·환불이
전부 같은 길로 갔다. **`spring-boot-starter-data-jpa` 가 청크 2 부터 남아 있었는데**
그 절은 「청크 6 에서 정한다」에 멈춰 있었다 — 청크 6 은 오래전에 끝났다.

**의존성 목록이 설계를 안 속이게 한다.** 남겨 두면 다음 사람이 Hibernate 가 도는 줄 알고,
`ddl-auto` 가 스키마를 지킨다고 오해한다. 스키마를 지키는 것은 Flyway 와 마이그레이션이다.

## 프론트에서 안 쓰는 것

**관례로 깔리는 넷을 안 쓴다.** `D23` 「안 넣은 것도 근거를 남긴다」가 요구하는 근거를
여기 적는다 — 없으면 다음 사람이 빠뜨린 줄 알고 채워 넣는다(`Q14`).

| 안 쓰는 것 | 관례상 기본 | 왜 안 쓰나 |
|---|---|---|
| 서버 상태 관리 | `@tanstack/react-query`·`swr` | **서버 컴포넌트가 기본이라 캐시 계층이 겹친다**(`D24`). 목록·상세는 서버가 그리고, 조작 뒤 갱신은 `router.refresh()` 가 서버에게 다시 물어본다 — 클라이언트가 들고 있을 상태가 없다 |
| 폼 | `react-hook-form` | 칸이 적고 검증이 **서버가 유일한 출처**다(`5-2`). 화면 검사는 편의고 판정이 아니라, 비제어 `FormData` 로 충분하다 |
| 날짜 | `date-fns`·`dayjs` | 로케일이 하나(`ko-KR`)고 시간대가 하나(`Asia/Seoul`)다. `toLocaleDateString` 이 그 둘을 다 받는다(`lib/format.ts`) |
| HTTP | `axios` | 입구가 셋뿐이고(`api.ts`) 인터셉터로 할 일을 그 셋이 이미 한다 — 표기 변환·CSRF·401 처리 |

**넷 다 값이 오르면 다시 본다.** 화면이 늘어 같은 데이터를 여러 곳에서 부르기 시작하거나,
칸이 많은 폼이 생기거나, 로케일이 둘이 되면 그때가 그 시점이다.

## 아직 안 정한 것

| 대상 | 언제 |
|---|---|
| Next.js 버전, 패키지 매니저 | 청크 13 |
| springdoc-openapi | 청크 2a |
| MinIO | 청크 26 |

정해지면 위 표에 줄을 더한다.

## 버전을 올릴 때

| 대상 | 올리면 볼 곳 |
|---|---|
| Spring Boot | 스타터 이름과 자동 설정. 마이너 버전에서도 바뀐 전례가 있다 |
| Testcontainers | Docker Engine 최소 지원 API 버전 |
| PostgreSQL | `transaction-iso` 문서. `D11` 이 Read Committed 동작에 기대고 있다 |
| Java | Gradle toolchain 과 CI 의 JDK |

**올린 뒤에는 `gradlew build` 와 `gradlew test` 를 실제로 돌린다.** 돌리지 않았으면 그렇게 적는다.

## 이 문서를 고칠 때

새 의존성이 들어오면 위 버전표에 줄을 더한다.
**기억과 실제가 어긋난 경험이 생기면 「기억으로 쓰면 틀리는 자리」에 적는다.** 그게 이 문서의 값이다.
