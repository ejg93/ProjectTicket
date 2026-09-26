# backend

`backend/` 의 파일을 건드릴 때만 실린다. 루트 `CLAUDE.md` 「코드를 쓸 때 항상 지키는 것」이 문서 단위라면 여기는 절 단위다 —
**건드리는 것이 펼 절을 정한다.** 명령은 `/verify` 스킬이 든다.

| 건드리는 것 | `coding-rules.md`(D14) | `naming-rules.md`(D15) | 그 밖 |
|---|---|---|---|
| 컨트롤러·서비스·`*Query` | 「계층」·「예외」 | 「이름을 짓는 자리」 | `api-guidelines.md`(D5) 상태 코드·응답 형식 |
| data class·DTO·조회 결과 | 「값」·「빈 값에 뜻을 싣지 않는다」 | 「DB 컬럼과 필드」 | Kotlin 널 타입이 「빈 값」의 첫 강제 지점이다 |
| SQL(`JdbcClient`) | 「SQL」 | 「SQL」 | **좌석 상태를 바꾸는 SQL 은 조건부 UPDATE 다**(`concurrency-rules.md`, D4) |
| 마이그레이션 `V*.sql` | 「마이그레이션」·「열거값을 어디에 두나」 | 「SQL」 | **시드를 여기 안 넣는다** — `local` 의 `DemoSeeder`(12). `HealthControllerTest` 는 파일 수를 스스로 센다(`B0-4`) — 올릴 수 없다. 새 `V*` 가 있으면 `verify.sh` 가 `integrationTest` 를 같이 돈다 |
| 테스트 | 「테스트」 | | `testing-strategy.md`(D8). DB 를 쓰면 `PostgresTestBase`(롤백) 또는 `ConcurrencyTestBase`(커밋 — 경쟁·지연 트리거·롤백 자체를 잴 때) 상속 — `db` 태그가 따라온다 |
| 로그 | | | `observability-rules.md`(D10) — 개인정보는 식별자만 |

표에 없는 것을 건드리면 D14 의 목차에서 절을 고르고 이 표에 행을 더한다.

**Kotlin 으로 쓴다.** `data class`·널 안전·`when`·확장 함수. `!!` 를 안 쓴다 — `checkNotNull(값) { 근거 }`, main 은 detekt 가 세운다. Java 스타일 getter/setter·builder 를 안 만든다.
