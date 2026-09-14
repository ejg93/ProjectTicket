# 도메인 모델

무엇이 무엇을 참조하고, 무엇이 같이 태어나고 죽나. 용어는 `glossary.md`.
**초안이다** — 청크 7~9 가 스키마를 세우면서 고친다.

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
| `performance` | 회차. 일시·홀·상태(`DRAFT→OPEN→CLOSED`)·판매 시작 시각 | event | event 와 함께 |
| `performance_seat` | **회차별 좌석 상태.** OPEN 때 `seat` 를 복제하고 등급·가격을 박제. `status`·`held_until`·`reservation_id` | performance | performance 와 함께 |
| `reservation` | 예매. 상태·계정·회차·합계·`held_until`·멱등키 | account | 영구(거래 기록) |
| `reservation_seat` | 예매 ↔ 회차 좌석. 좌석 하나에 살아있는 예매 하나 | reservation | reservation 과 함께 |
| `payment` | 모의 결제. 예매 하나에 하나 | reservation | 영구 |
| `refund` | 환불. 수수료·환불액·사유 | payment | 영구 |
| `ticket` | 발권. 외부 노출 번호, 예매 좌석 하나에 하나 | reservation_seat | 영구 |
| `outbox` | 발행 대기 이벤트 | — | 발행 후 보존 기간 뒤 삭제 |
| `audit_log` | 누가 무엇을 언제 | — | 수정 불가 |

Redis 에 두는 것 — 대기열 ZSET(회차 단위), 활성 토큰(TTL), 좌석 현황 캐시. **날아가도 사고가 아닌 것만** 둔다. 좌석 상태·멱등키는 DB 다.

## 왜 회차마다 좌석을 복제하나

같은 홀이라도 공연마다 등급·가격이 다르고, 상태(AVAILABLE·HELD·RESERVED)는 회차 단위다.
`seat` 에 상태를 두면 회차 둘이 같은 홀을 쓸 때 부딪친다. 복제 비용은 회차당 좌석 수(수천 행)라 오픈 한 번에 감당된다.

## 좌석 상태의 단일 진실

`performance_seat.status` 하나다. 선점은 `UPDATE … SET status='HELD', held_until=…, reservation_id=… WHERE performance_seat_id IN (…) AND status='AVAILABLE'` 의 갱신 행 수로 판정한다.
Redis 캐시는 조회를 덜어 주는 것이고 판정에 안 쓴다. 상세는 `concurrency-rules.md`(D4).

## 경계

| 경계 | 안 | 밖 |
|---|---|---|
| 공연 관리 | organizer·venue·hall·seat·event·seat_grade·performance | 좌석 상태 변경 |
| 예매 | performance_seat 상태·reservation·reservation_seat·ticket | 결제 승인 |
| 결제 | payment·refund | 좌석 해제(예매가 이벤트로 받는다) |
| 대기열 | Redis 만 | DB |
| 정산 | 회차 종료 후 집계 | 실시간 |
