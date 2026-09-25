# 변이 시험 — 첫 판 (G4)

2026-09-25. `gradlew mutationTest`(PIT 1.30.0 명령줄 · `pitest-junit5-plugin` 1.2.3 · 기본 변이 · 스레드 4). 시험은 빠른 레인 전체(`db`·`measure` 제외)를 주고 PIT 가 변이를 덮는 것만 골라 돈다. 한 판 14초.

## 판마다

| 판 | 대상 | 변이 | 죽임 | 산 것 | 덮지 않음 | 무엇을 바꿨나 |
|---|---|---|---|---|---|---|
| 1 | 글롭 여섯 | 62 | 32 | 7 | 23 | — |
| 2 | 이름 여덟 | 56 | 52 | 4 | 0 | 글롭이 `CardNumberTest`·`SectionCodesTest` 까지 변이해서 이름을 다 적었다. `PagingTest`·`IdempotencyKeysTest` 신설, `CardNumberTest` 에 19 자리 |
| 3 | 이름 여덟 | 56 | **53** | **3** | 0 | `CardNumberTest` 거절 사례에 `:` |

## 살아남은 것의 처분

| 자리 | 변이 | 처분 |
|---|---|---|
| `Paging`·`OrderBy` 13 · `IdempotencyKeys.require` 7 | 덮지 않음 | **시험을 더했다.** 컨테이너 레인(`EventListTest`·선점·결제 흐름)만 거쳐서 빠른 레인이 하나도 못 덮었다 |
| `CardNumbers.isWellShaped:48` 길이 상한 | `<= 19` → `< 19` | **시험을 더했다** — 통과 사례가 12 자리뿐이었다 |
| `CardNumbers.isWellShaped:48` 글자 상한 | `c < ':'` → `c <= ':'` | **시험을 더했다** — `:` 가 숫자로 세이면 `card_last4` 가 되어 `payment_card_last4_format_check` 에 걸린다(400 이 500) |
| `SectionCodesValidator.isValid:42` | 조건 뒤집기 | **동치.** Kotlin 인라인 `all` 이 넣은 `is Collection` 분기다. `List` 는 늘 `Collection` 이라 뒤집어도 빈 목록은 루프가 같은 `true` 를 낸다 |
| `OrderBy.Companion.split:72` · `IdempotencyKeys.require:16` | `Intrinsics.checkNotNullExpressionValue` 호출 삭제 | **동치.** Kotlin 컴파일러가 플랫폼 타입(`trim()`·`lowercase()` 의 Java 반환) 뒤에 넣은 널 검사다. 그 값은 널이 안 나온다 |

## PIT 가 못 만드는 변이

`RefundPolicy` 는 변이가 둘(`daysBefore`·`fee` 가 0 을 돌려준다)뿐이다. 기본 변이는 `RoundingMode.HALF_UP` 같은 열거 상수를 안 바꾼다.
그래서 반올림 모드는 손으로 바꿔 봤다 — `HALF_DOWN` 이면 `fee_is_rounded_once_on_the_total_half_up` 가 `expected: 1 but was: 0` 으로 빨갛다.

PIT 는 Arcmutate 의 Kotlin 플러그인이 위 동치 둘(널 검사 호출·인라인 `is Collection`)을 걸러 준다고 알린다. 들이지 않았다 — 유료이고, 동치는 이 표에 근거를 적어 둔 것으로 끝난다.
