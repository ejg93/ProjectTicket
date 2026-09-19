import type { Metadata } from "next";
import Link from "next/link";

import { Countdown } from "@/components/countdown";
import { apiSession } from "@/lib/api-session";
import { dateTime, money } from "@/lib/format";

import { CheckoutForm } from "./checkout-form";

export const metadata: Metadata = {
  title: "예매 확인 · ProjectTicket",
};

/** 서버 JSON 이 snake_case 라 **받은 그대로 적는다**(`D5`) */
type Reservation = {
  reservation_id: number;
  performance_id: number;
  status: string;
  total_amount: number;
  held_until: string;
  paying_until: string | null;
  seats: { performance_seat_id: number; price: number }[];
};

type Ticket = {
  ticket_number: string;
  section: string;
  row_label: string;
  seat_number: number;
  price: number;
  issued_at: string;
};

/**
 * 한 예매의 지금.
 *
 * **상태가 화면을 고른다**(`D3` 의 예매 상태기계). 잡은 것은 결제 폼, 낸 것은 티켓, 풀린 것은 안내다 —
 * 한 화면에 다 그리고 숨기면 어떤 상태에서 무엇이 보이는지가 CSS 에 흩어진다.
 *
 * 로그인해야 본다. 세션이 없으면 {@link apiSession} 이 로그인 화면으로 보낸다(`D16`).
 */
export default async function ReservationPage({
  params,
}: {
  params: Promise<{ reservationId: string }>;
}) {
  const { reservationId } = await params;
  const reservation = await apiSession<Reservation>(`/api/reservations/${reservationId}`);

  // 발권은 승인 트랜잭션 안에서 끝난다(`18`). 그래서 `reserved` 면 티켓이 이미 있다.
  const tickets =
    reservation.status === "reserved"
      ? await apiSession<Ticket[]>(`/api/reservations/${reservationId}/tickets`)
      : [];

  return (
    <div>
      <h1>예매 확인</h1>
      <p>
        좌석 {reservation.seats.length}석 · {money(reservation.total_amount)}
      </p>

      {reservation.status === "held" ? (
        <>
          <Countdown deadline={reservation.held_until} label="남은 시간" />
          <CheckoutForm
            reservationId={reservation.reservation_id}
            amount={reservation.total_amount}
          />
        </>
      ) : null}

      {/*
        결제가 시작된 창이다(ADR 0004). 사용자가 카드사 화면에 있는 동안이라 **여기서 또 내라고 안 한다** —
        두 번 긁는 길을 화면이 열면 안 된다.
      */}
      {reservation.status === "paying" && reservation.paying_until ? (
        <Countdown deadline={reservation.paying_until} label="결제 처리 중입니다. 남은 시간" />
      ) : null}

      {reservation.status === "reserved" ? (
        <>
          <h2>티켓</h2>
          <ul>
            {tickets.map((ticket) => (
              <li key={ticket.ticket_number}>
                {ticket.ticket_number} · {ticket.section} {ticket.row_label}열{" "}
                {ticket.seat_number}번 · {money(ticket.price)} · {dateTime(ticket.issued_at)}
              </li>
            ))}
          </ul>
        </>
      ) : null}

      {reservation.status === "expired" || reservation.status === "cancelled" ? (
        <p role="status">
          {reservation.status === "expired"
            ? "선점 시간이 지나 좌석이 풀렸습니다. 좌석을 다시 골라 주세요."
            : "취소된 예매입니다."}
        </p>
      ) : null}

      <p className="muted">
        <Link href={`/performances/${reservation.performance_id}`}>좌석 현황</Link> ·{" "}
        <Link href="/events">공연 목록</Link>
      </p>
    </div>
  );
}
