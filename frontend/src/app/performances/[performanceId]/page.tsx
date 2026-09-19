import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";

import { SeatMap, type SeatMapData } from "@/components/seat-map";
import { ApiError, apiPublic } from "@/lib/api";

export const metadata: Metadata = {
  title: "좌석 선택 · ProjectTicket",
};

/**
 * 좌석 선택.
 *
 * **첫 그림은 서버가 읽는다**(`D16`) — 로그인 전에도 본다(`SecurityConfig.PUBLIC_PATHS`). 고르는 것만
 * 클라이언트로 내린다(`SeatMap`).
 *
 * **폴링을 여기서 안 켠다.** 델타(`/seats/changes`)와 카운트다운은 예매 흐름(`42`)이 붙인다 —
 * 지금 켜면 고르는 동안 남의 선점이 내 선택을 지우는데 그것을 어떻게 알릴지가 그 청크의 결정이다.
 */
export default async function PerformanceSeatsPage({
  params,
}: {
  params: Promise<{ performanceId: string }>;
}) {
  const { performanceId } = await params;

  let seats: SeatMapData;
  try {
    seats = await apiPublic<SeatMapData>(`/api/performances/${performanceId}/seats`);
  } catch (thrown) {
    // 없는 회차·아직 안 연 회차는 404 다(`10`). 5xx 를 404 로 바꾸지 않는다 — 고장이 없는 주소로 보인다.
    if (thrown instanceof ApiError && thrown.slug === "performance-not-found") {
      notFound();
    }
    throw thrown;
  }

  return (
    <div>
      <h1>좌석 선택</h1>
      <SeatMap data={seats} />
      <p className="muted">
        <Link href="/events">공연 목록</Link>
      </p>
    </div>
  );
}
