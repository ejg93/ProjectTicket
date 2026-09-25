import Link from "next/link";

import { dateTime, money } from "@/lib/format";
import { reservationStatusLabel } from "@/lib/reservation-status";

import { CancelForm } from "./cancel-form";

/** `GET /api/me/reservations` 의 한 줄(`44a`). 서버 JSON 이 snake_case 라 **받은 그대로 적는다**(`D5`) */
export type MyReservation = {
  reservation_id: number;
  performance_id: number;
  event_title: string;
  starts_at: string;
  status: string;
  total_amount: number;
  seat_count: number;
  created_at: string;
};

/**
 * 내 예매 목록의 그림. 페이지(서버)가 읽고 여기는 그리기만 한다 — 시험이 같은 마크업을 그려 접근성을 잰다.
 * **취소는 `reserved` 에만 둔다** — 다른 상태에서 누르면 서버가 `invalid-transition` 으로 막는 것을 화면이 먼저 안 연다.
 */
export function ReservationList({ items }: { items: MyReservation[] }) {
  if (items.length === 0) {
    return <p>아직 예매가 없습니다.</p>;
  }
  return (
    <ul>
      {items.map((item) => (
        <li key={item.reservation_id}>
          <h2>
            <Link href={`/reservations/${item.reservation_id}`}>{item.event_title}</Link>
          </h2>
          <p>
            {dateTime(item.starts_at)} · 좌석 {item.seat_count}석 · {money(item.total_amount)} ·{" "}
            {reservationStatusLabel(item.status)}
          </p>
          {item.status === "reserved" ? <CancelForm reservationId={item.reservation_id} /> : null}
        </li>
      ))}
    </ul>
  );
}
