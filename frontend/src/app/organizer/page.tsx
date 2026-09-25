import type { Metadata } from "next";
import Link from "next/link";

import { apiSession } from "@/lib/api-session";
import { dateTime } from "@/lib/format";

import { isNoOrganizerRole, NoOrganizerRole } from "./no-role";
import type { OrganizerEvent } from "./types";

export const metadata: Metadata = {
  title: "기획사 · ProjectTicket",
};

type Page = { items: OrganizerEvent[]; page: number; size: number; total: number };

/**
 * 기획사 — 내 공연 목록(`45b`). 오픈 전 공연도 보인다(`45a`). 역할이 아니면 서버가 403 `organizer-forbidden` 을 준다 —
 * 그 판정은 서버 경로 접두 한 군데다(`D17`). 넘김은 번호 페이징이다(`D17` 「목록 넘김」).
 */
export default async function OrganizerPage({ searchParams }: { searchParams: Promise<{ page?: string }> }) {
  const { page: raw } = await searchParams;
  const current = Math.max(0, Number.parseInt(raw ?? "0", 10) || 0);

  let list: Page;
  try {
    list = await apiSession<Page>(`/api/organizer/events?page=${current}`);
  } catch (thrown) {
    if (isNoOrganizerRole(thrown)) return <NoOrganizerRole />;
    throw thrown;
  }
  const last = Math.max(0, Math.ceil(list.total / list.size) - 1);

  return (
    <div>
      <h1>기획사</h1>
      <p>
        <Link href="/organizer/events/new">공연 등록</Link>
      </p>
      {list.items.length === 0 ? (
        <p>아직 등록한 공연이 없습니다.</p>
      ) : (
        <ul>
          {list.items.map((event) => (
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
      {list.total > list.size ? (
        <nav aria-label="쪽">
          {list.page > 0 ? <Link href={`/organizer?page=${list.page - 1}`}>이전</Link> : null}{" "}
          {list.page < last ? <Link href={`/organizer?page=${list.page + 1}`}>다음</Link> : null}
        </nav>
      ) : null}
    </div>
  );
}
