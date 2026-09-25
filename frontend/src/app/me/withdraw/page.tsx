import type { Metadata } from "next";

import { ComingSoon } from "@/components/coming-soon";

export const metadata: Metadata = {
  title: "회원 탈퇴 · ProjectTicket",
};

/** 자리표시(`39-1`). `44c` 가 실물로 바꾸고 이 파일을 지운다 */
export default function WithdrawPage() {
  return <ComingSoon title="회원 탈퇴" />;
}
