import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { ApiError, apiPublic } from "@/lib/api";

import { QueueStatus } from "./queue-status";

export const metadata: Metadata = {
  title: "대기열 · ProjectTicket",
};

/** `GET /api/performances/{id}`(`40c`) 에서 이 화면이 쓰는 칸 */
type Performance = { title: string; hall_name: string; venue_name: string };

/**
 * 대기열(`43`, `D12`). 선점이 `admission-required` 를 받으면 여기로 온다.
 *
 * **제목은 서버가 읽는다**(`D16`) — 무엇을 기다리는지가 첫 그림에 있어야 한다. 줄 서기와 폴링은 클라이언트다([QueueStatus]).
 * 로그인은 줄에서 확인된다 — 401 이면 `api.ts` 가 로그인 화면으로 보낸다.
 */
export default async function QueuePage({ params }: { params: Promise<{ performanceId: string }> }) {
  const { performanceId } = await params;

  let performance: Performance;
  try {
    performance = await apiPublic<Performance>(`/api/performances/${performanceId}`);
  } catch (thrown) {
    if (thrown instanceof ApiError && thrown.slug === "performance-not-found") {
      notFound();
    }
    throw thrown;
  }

  return (
    <div>
      <h1>{performance.title} 대기열</h1>
      <p className="muted">
        {performance.venue_name} {performance.hall_name}
      </p>
      <QueueStatus performanceId={performanceId} />
    </div>
  );
}
