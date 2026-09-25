import type { Metadata } from "next";
import Link from "next/link";

import { apiSession } from "@/lib/api-session";
import { dateTime } from "@/lib/format";

import type { OrganizerEvent } from "./types";

export const metadata: Metadata = {
  title: "기획사 · ProjectTicket",
};

/**
 * 기획사 — 내 공연 목록(`45b`). 오픈 전 공연도 보인다(`45a`). 역할이 아니면 서버가 403 `organizer-forbidden` 을 준다 —
 * 그 판정은 서버 경로 접두 한 군데다(`D17`). 링크는 `/me` 에서만 건다.
 */
export default async function OrganizerPage() {
  const page = await apiSession<{ items: OrganizerEvent[]; total: number }>("/api/organizer/events?size=100");

  return (
    <div>
      <h1>기획사</h1>
      <p>
        <Link href="/organizer/events/new">공연 등록</Link>
      </p>
      {page.items.length === 0 ? (
        <p>아직 등록한 공연이 없습니다.</p>
      ) : (
        <ul>
          {page.items.map((event) => (
            <li key={event.event_id}>
              <Link href={`/organizer/events/${event.event_id}`}>{event.title}</Link>
              <span className="muted">
                {" "}
                · {event.organizer_name} · 회차 {event.performance_count}개 · 등록 {dateTime(event.created_at)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
