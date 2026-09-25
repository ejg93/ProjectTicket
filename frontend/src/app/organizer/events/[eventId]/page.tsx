import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";

import { ApiError } from "@/lib/api";
import { apiSession } from "@/lib/api-session";
import { dateTime, money } from "@/lib/format";

import { isNoOrganizerRole, NoOrganizerRole } from "../../no-role";
import { performanceStatusLabel, type Hall, type OrganizerEventDetail } from "../../types";

import { PerformanceActions } from "./performance-actions";
import { PerformanceForm } from "./performance-form";
import { SettlementSummary, type Settlement, type SettlementSlot } from "./settlement-summary";

export const metadata: Metadata = {
  title: "공연 관리 · ProjectTicket",
};

/**
 * 공연 하나(`45b`) — 등급, 회차마다 상태·판매 기간·팔린 수·정산(`45c`), 회차 등록과 오픈·취소.
 * 남의 공연은 서버가 404 다(`45a`) — 여기서도 없는 화면으로 그린다(`D16` 「403·404 는 부르는 화면이 잡는다」).
 */
export default async function OrganizerEventPage({ params }: { params: Promise<{ eventId: string }> }) {
  const { eventId } = await params;

  let event: OrganizerEventDetail;
  let halls: Hall[];
  try {
    [event, halls] = await Promise.all([
      apiSession<OrganizerEventDetail>(`/api/organizer/events/${eventId}`),
      apiSession<Hall[]>("/api/organizer/halls"),
    ]);
  } catch (thrown) {
    if (thrown instanceof ApiError && thrown.slug === "event-not-found") {
      notFound();
    }
    if (isNoOrganizerRole(thrown)) return <NoOrganizerRole />;
    throw thrown;
  }

  // 회차마다 정산서 하나(`45c`). 아직 없으면 404 라 `"none"` — 남의 회차도 같은 404 지만 여기 오는 회차는 이미 내 것이다.
  // 그 밖의 실패는 그 칸만 「못 불러왔다」다 — 곁가지 칸 하나가 회차 등록·오픈·취소까지 막으면 안 된다(마무리 14차).
  const settlements = await Promise.all(
    event.performances.map((p) =>
      apiSession<Settlement>(`/api/organizer/settlements?performanceId=${p.performance_id}`).catch(
        (thrown: unknown): SettlementSlot =>
          thrown instanceof ApiError && thrown.slug === "performance-not-found" ? "none" : "failed",
      ),
    ),
  );

  return (
    <div>
      <h1>{event.title}</h1>
      <p className="muted">{event.organizer_name}</p>

      <h2>등급</h2>
      <ul>
        {event.grades.map((g) => (
          <li key={g.code}>
            {g.name} · {money(g.price)}
          </li>
        ))}
      </ul>

      <h2>회차</h2>
      {event.performances.length === 0 ? <p>아직 회차가 없습니다.</p> : null}
      <ul>
        {event.performances.map((p, i) => (
          <li key={p.performance_id}>
            <p>
              {dateTime(p.starts_at)} · {p.venue_name} {p.hall_name} · {performanceStatusLabel(p.status)}
            </p>
            <p className="muted">
              판매 {dateTime(p.sales_open_at)} ~ {dateTime(p.sales_close_at)} · 팔린 좌석 {p.sold_count} / {p.seat_count}
            </p>
            <SettlementSummary settlement={settlements[i]} />
            <PerformanceActions
              performanceId={p.performance_id}
              status={p.status}
              label={`${dateTime(p.starts_at)} ${p.hall_name}`}
            />
          </li>
        ))}
      </ul>

      <h2>회차 등록</h2>
      <PerformanceForm eventId={event.event_id} halls={halls} />

      <p className="muted">
        <Link href="/organizer">내 공연 목록</Link>
      </p>
    </div>
  );
}
