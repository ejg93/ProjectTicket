import Link from "next/link";

import { dateTime, money } from "@/lib/format";

/** 서버 JSON 이 snake_case 라 **받은 그대로 적는다**(`D5`) */
export type EventSummary = {
  event_id: number;
  title: string;
  organizer_name: string;
  starts_at: string;
  performance_count: number;
  min_price: number | null;
};

export type EventPage = {
  items: EventSummary[];
  page: number;
  size: number;
  total: number;
};

/**
 * 공연 목록의 줄과 쪽 이동.
 *
 * **비어 있는 것을 화면이 말한다**(`D17` 「빈 상태」). 아무것도 안 그리면 고장과 구분이 안 되고,
 * 사용자는 새로고침을 누른다. 여기는 「아직 없음」이라 **무엇을 하면 생기는지**가 아니라
 * 언제 다시 오면 되는지를 적는다 — 파는 것은 기획사지 사용자가 아니다.
 *
 * 값을 받아 그리기만 한다. 부르는 것은 페이지가 하고, 그래서 테스트가 이 조각만 그려 볼 수 있다(`D8`).
 */
export function EventList({ events }: { events: EventPage }) {
  if (events.items.length === 0) {
    return <p>판매 중인 공연이 없습니다. 예매가 열리면 여기에 표시됩니다.</p>;
  }

  const lastPage = Math.max(0, Math.ceil(events.total / events.size) - 1);

  return (
    <div>
      <ul>
        {events.items.map((event) => (
          <li key={event.event_id}>
            <Link href={`/events/${event.event_id}`}>{event.title}</Link>
            <p className="muted">
              {event.organizer_name} · 첫 회차 {dateTime(event.starts_at)} · 회차{" "}
              {event.performance_count}개
              {/*
                등급을 아직 안 만든 공연은 `min_price` 가 `null` 이다. **0원으로 그리지 않는다** —
                공짜 공연과 안 갈린다(`D5` 「null 과 생략」).
              */}
              {event.min_price === null ? null : ` · ${money(event.min_price)}부터`}
            </p>
          </li>
        ))}
      </ul>

      {/* 쪽이 하나면 이동을 안 그린다. 누를 것이 없는 버튼은 길만 늘린다(`D17`) */}
      {lastPage === 0 ? null : (
        <nav aria-label="쪽 이동">
          {events.page > 0 ? <Link href={`/events?page=${events.page - 1}`}>이전</Link> : null}{" "}
          <span>
            {events.page + 1} / {lastPage + 1}
          </span>{" "}
          {events.page < lastPage ? (
            <Link href={`/events?page=${events.page + 1}`}>다음</Link>
          ) : null}
        </nav>
      )}
    </div>
  );
}
