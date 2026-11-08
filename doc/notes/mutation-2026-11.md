# 변이 시험 — 기준선 (I14-2)

2026-11-08. `gradlew mutationTest`(PIT 1.30.1 명령줄 · `pitest-junit5-plugin` 1.2.3 · 기본 변이 · 스레드 4). 대상은 `mutationTargets` 여덟 그대로, 첫 판 이력·PIT 가 못 만드는 변이는 `mutation-2026-09.md`.
9월 노트 뒤 `Paging.kt`·`IdempotencyKeys.kt` 가 바뀌었는데 안 돌았다 — 규칙이 문서에만 있었다. 이 판부터 `scripts/mutation-gate.sh` 가 대상 소스를 고친 `verify.sh` 판에서 같이 돌고, 아래 표가 기준선이다.

| 변이 | 죽임 | 산 것 | 덮지 않음 |
|---|---|---|---|
| 54 | 51 | 3 | 0 |

`SectionCodesValidator.isValid:42` 의 줄은 인라인 `all` 이 붙인 합성 줄이라 파일 끝에 줄을 더해도 밀린다(2026-11-08 실측 — 한 줄 더하니 `:43`). 밀리면 게이트가 빨갛고 자리를 다시 적는다.

9월 판(56)보다 둘 적다 — `Paging.kt` 가 바뀌어 변이 자리가 줄었다. 산 셋은 9월의 동치 셋과 같고 줄만 밀렸다.

## 살아남은 것의 처분

`mutation-gate.sh` 가 이 표의 백틱 자리를 읽는다 — 자리는 `클래스.메서드:줄`, 중첩 클래스는 `.` 로 잇는다.

| 자리 | 변이 | 처분 |
|---|---|---|
| `OrderBy.Companion.split:85` | `Intrinsics.checkNotNullExpressionValue` 호출 삭제 | **동치.** 플랫폼 타입(`trim()`·`lowercase()` 의 Java 반환) 뒤 컴파일러가 넣은 널 검사다. 그 값은 널이 안 나온다(9월 `:72`) |
| `IdempotencyKeys.require:22` | `Intrinsics.checkNotNullExpressionValue` 호출 삭제 | **동치.** 같은 이유(9월 `:16`) |
| `SectionCodesValidator.isValid:42` | 조건 뒤집기 | **동치.** Kotlin 인라인 `all` 이 넣은 `is Collection` 분기다. `List` 는 늘 `Collection` 이라 뒤집어도 빈 목록은 같은 `true` 다 |
