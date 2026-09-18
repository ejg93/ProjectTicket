# 도메인 모델

무엇이 무엇을 참조하고, 무엇이 같이 태어나고 죽나. 용어는 `glossary.md`.
청크 7~9 가 스키마를 세웠고, 설계 점검(ADR 0004)이 예매·회차의 상태와 읽기 모델을 더했다. 13 뒤에 다시 본다.

## 엔티티

| 엔티티 | 무엇 | 소유자 | 수명 |
|---|---|---|---|
| `account` | 관객·기획사 구성원·관리자 계정. 역할 셋 | — | 탈퇴 시 개인정보 파기, 행은 남긴다(예매 이력 FK) |
| `organizer` | 기획사. 공연을 올리고 정산을 받는다 | — | 삭제 안 함 |
| `organizer_member` | 계정 ↔ 기획사 소속 | organizer | 기획사와 함께 |
| `venue` | 공연장 | — | 삭제 안 함 |
| `hall` | 공연장 안의 홀. 좌석 배치는 홀 단위 | venue | venue 와 함께 |
| `seat` | 물리 좌석. `(hall_id, section, row_label, seat_number)` 유일 | hall | hall 과 함께. **회차가 참조하므로 수정 불가**, 배치가 바뀌면 새 홀 |
| `event` | 공연(작품). 제목·기획사·기간 | organizer | 회차가 없으면 삭제 가능 |
| `seat_grade` | 공연의 등급(VIP·R·S)과 가격 | event | event 와 함께 |
| `seat_grade_map` | 홀 좌석 → 등급. 공연마다 다르다 | event | event 와 함께 |
| `performance` | 회차. 일시·홀·상태(`draft→open→closed`, `open→cancelled`)·판매 시작·**판매 마감**(`sales_close_at`, 16a) | event | event 와 함께 |
| `performance_seat` | **회차별 좌석 상태.** OPEN 때 `seat` 를 복제하고 등급·가격을 박제. `status`·`held_until`·`reservation_id`(**포인터** — 지금 이 좌석을 쥔 예매) | performance | performance 와 함께 |
| `reservation` | 예매. 상태(`held→paying→reserved→cancelled`, `→expired`, `D3`)·계정·회차·합계·`held_until`·`paying_until`·멱등키 | account | 영구(거래 기록) |
| `reservation_seat` | **기록** — 이 예매가 잡은 좌석과 그때 가격. 선점 때 한 번 쓰고 안 고친다(`D4`) | reservation | reservation 과 함께 |
| `idempotency_key` | 계정별 멱등키와 저장된 응답. 24시간(`D4`) | account | 24시간 뒤 삭제(`IdempotencyCleaner`, `13b`) |
| `payment` | 모의 결제. 예매 하나에 하나 | reservation | 영구 |
| `refund` | 환불. 수수료·환불액·사유 | payment | 영구 |
| `ticket` | 발권. 외부 노출 번호, 예매 좌석 하나에 하나 | reservation_seat | 영구 |
| `outbox` | 발행 대기 이벤트 | — | 발행 후 7일 뒤 삭제(`OutboxCleaner`, 25a). 재발행 요구가 없어서 짧다 |
| `audit_log` | 누가 무엇을 언제 | — | 수정 불가 |

Redis 에 두는 것 — 대기열 ZSET·활성 토큰(`D12`), 좌석 버전·스냅샷·변경 로그(`D20`), **세션**(ADR 0004, 20a). **날아가도 사고가 아닌 것만** 둔다 — 세션은 날아가면 다시 로그인이고, 좌석 상태·멱등키는 DB 다.

## 왜 회차마다 좌석을 복제하나

같은 홀이라도 공연마다 등급·가격이 다르고, 상태(AVAILABLE·held·reserved)는 회차 단위다.
`seat` 에 상태를 두면 회차 둘이 같은 홀을 쓸 때 부딪친다. 복제 비용은 회차당 좌석 수(수천 행)라 오픈 한 번에 감당된다.

## 좌석 상태의 단일 진실

`performance_seat.status` 하나다. 선점은 조건부 UPDATE 의 갱신 행 수로 판정한다 — 문장과 잠금 순서는 `concurrency-rules.md`(D4).
Redis 는 조회를 덜어 주는 것이고(`D20`) 판정에 안 쓴다.

**좌석 상태는 예매 상태의 투영이다.** 예매가 진실이고 좌석은 그것을 좌석 단위로 편 것이다. 둘의 대응표는 `state-machines.md`(D3).

## 경계

| 경계 | 안 | 밖 |
|---|---|---|
| 공연 관리 | organizer·venue·hall·seat·event·seat_grade·performance | 좌석 상태 변경 |
| 예매 | performance_seat 상태·reservation·reservation_seat·ticket | 결제 승인 |
| 결제 | payment·refund | 좌석 해제(예매가 이벤트로 받는다) |
| 대기열 | Redis 만. 관문은 선점에만 걸린다(ADR 0004) | DB, 결제·취소·조회 |
| 좌석 읽기 | Redis 버전·스냅샷·델타 | 좌석 상태 결정 |
| 정산 | 회차 종료 후 집계 | 실시간 |
