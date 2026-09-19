import type { Metadata } from "next";
import Link from "next/link";

import { apiPublic } from "@/lib/api";

import { EventList, type EventPage } from "./event-list";

export const metadata: Metadata = {
  title: "공연 목록 · ProjectTicket",
};

/**
 * 공연 목록.
 *
 * 서버 컴포넌트다 — 읽기뿐이라 클라이언트로 내릴 이유가 없다(`D16` 「서버 컴포넌트가 기본이다」).
 * 로그인 전에 보는 화면이라 {@link apiPublic} 다.
 *
 * **쪽 번호를 주소에 둔다**(`D16` 「상태는 주소에 둔다」). 그래야 목록의 어느 쪽을 보고 있었는지가
 * 뒤로가기와 새로고침에 남고, 링크로 넘길 수 있다.
 */
export default async function EventsPage({
  searchParams,
}: {
  searchParams: Promise<{ page?: string }>;
}) {
  const { page } = await searchParams;
  const requested = Number(page);
  // 주소는 사람이 고칠 수 있다. 숫자가 아니면 첫 쪽이다 — 서버에 그대로 넘기면 400 을 받고 화면이 선다.
  const current = Number.isInteger(requested) && requested > 0 ? requested : 0;

  const events = await apiPublic<EventPage>(`/api/events?page=${current}`);

  return (
    <div>
      <h1>공연</h1>
      <EventList events={events} />
      <p className="muted">
        <Link href="/">처음으로</Link>
      </p>
    </div>
  );
}
