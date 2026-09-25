import type { Metadata } from "next";

import { ComingSoon } from "@/components/coming-soon";

export const metadata: Metadata = {
  title: "대기열 · ProjectTicket",
};

/** 자리표시(`39-1`). `43` 가 실물로 바꾸고 이 파일을 지운다 */
export default function QueuePage() {
  return <ComingSoon title="대기열" />;
}
