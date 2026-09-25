import type { Metadata } from "next";
import Link from "next/link";

import { apiSession } from "@/lib/api-session";

import { ReservationList, type MyReservation } from "./reservation-list";

export const metadata: Metadata = {
  title: "내 예매 · ProjectTicket",
};

type Page = { items: MyReservation[]; page: number; size: number; total: number };

/**
 * 내 예매 목록(`44b`). **첫 그림은 서버가 읽는다**(`D16`) — 로그인이 끊겼으면 {@link apiSession} 이 로그인 화면으로 보낸다.
 * 넘김은 번호 페이징이다(`D17` 「목록 넘김」) — 주소가 곧 상태라 뒤로 가기·새로고침이 저절로 된다.
 */
export default async function MyReservationsPage({ searchParams }: { searchParams: Promise<{ page?: string }> }) {
  const { page: raw } = await searchParams;
  const page = Math.max(0, Number.parseInt(raw ?? "0", 10) || 0);
  const list = await apiSession<Page>(`/api/me/reservations?page=${page}`);
  const last = Math.max(0, Math.ceil(list.total / list.size) - 1);

  return (
    <div>
      <h1>내 예매</h1>
      <ReservationList items={list.items} />
      {list.total > list.size ? (
        <nav aria-label="쪽">
          {list.page > 0 ? <Link href={`/me/reservations?page=${list.page - 1}`}>이전</Link> : null}{" "}
          {list.page < last ? <Link href={`/me/reservations?page=${list.page + 1}`}>다음</Link> : null}
        </nav>
      ) : null}
      <p className="muted">
        <Link href="/me">내 계정</Link>
      </p>
    </div>
  );
}
