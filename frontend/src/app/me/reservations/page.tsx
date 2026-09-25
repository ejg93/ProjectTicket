import type { Metadata } from "next";

import { ComingSoon } from "@/components/coming-soon";

export const metadata: Metadata = {
  title: "내 예매 · ProjectTicket",
};

/** 자리표시(`39-1`). `44b` 가 실물로 바꾸고 이 파일을 지운다 */
export default function MyReservationsPage() {
  return <ComingSoon title="내 예매" />;
}
