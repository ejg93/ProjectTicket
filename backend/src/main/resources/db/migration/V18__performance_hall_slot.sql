-- 같은 홀의 같은 시각에 회차가 둘일 수 없다(11, 마무리 4차 독립 리뷰가 열었다).
--
-- `OrganizerEventService.createPerformance` 가 `DuplicateKeyException` 을 잡아 409 를 내고 있었는데 **그 제약이 없었다** —
-- 한 번도 안 돈 강제 지점이다. 둘이 들어가면 각 회차가 그 홀의 좌석을 통째로 복제하므로(`PerformanceOpenService.copySeats`)
-- 같은 물리 좌석이 두 번 팔린다. 앱 검증이 아니라 제약으로 내리는 자리다(`D14` 「축 2 — 강제 지점」).
--
-- **취소된 회차는 자리를 안 막는다.** 전체 유일로 걸면 취소한 회차가 그 홀·시각을 영구히 점유해서 다시 못 올린다.
-- `closed` 는 판 이력이 남은 것이라 막는 쪽이 맞다 — 같은 시각에 또 열면 그 홀이 실제로 겹친다.
create unique index performance_hall_slot_key
    on performance (hall_id, starts_at)
 where status <> 'cancelled';
