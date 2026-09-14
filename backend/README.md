# backend

Kotlin + Spring Boot 4 서버. 좌석 선점·예매·결제·대기열.

## 띄우기

DB 가 떠 있어야 한다(루트 `README.md`). 이 환경은 기본 JDK 가 11 이라 앞에 JDK 25 를 지정한다.

```bash
JAVA_HOME="C:/Program Files/Java/jdk-25" ./gradlew bootRun
curl localhost:8080/api/health
```

`applied_migrations` 가 `db/migration/` 의 파일 수와 같아야 한다.
데모 데이터까지 넣으려면 `--args='--spring.profiles.active=local'`(청크 12 부터).

## 테스트

| 명령 | 무엇 | 언제 |
|---|---|---|
| `./gradlew test` | 컨테이너 없는 빠른 레인 | 고치는 중, 청크를 닫을 때 |
| `./gradlew integrationTest` | Testcontainers 로 Postgres 를 띄우는 레인(`db` 태그) | 스키마·서비스를 볼 때 |
| `./gradlew build` | 둘 다 + 정적 검사 | push 앞 |

느린 레인이 전부 실패하면 Docker Desktop 부터 본다. 컨테이너 재사용을 켜면 기동이 한 번으로 준다 —
`~/.testcontainers.properties` 에 `testcontainers.reuse.enable=true`.
