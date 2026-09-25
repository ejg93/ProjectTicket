import type { Metadata } from "next";
import Link from "next/link";

import { WithdrawForm } from "./withdraw-form";

export const metadata: Metadata = {
  title: "회원 탈퇴 · ProjectTicket",
};

/**
 * 회원 탈퇴(`44c`, 개인정보 보호법 제21조). **무엇이 사라지고 무엇이 남는지 먼저 말한다** — 누르고 나서 알면 늦다.
 * 로그인은 폼의 입구(`DELETE /api/me`)가 확인한다 — 세션이 없으면 `api.ts` 가 로그인 화면으로 보낸다.
 */
export default function WithdrawPage() {
  return (
    <div>
      <h1>회원 탈퇴</h1>
      <p>탈퇴하면 바로 로그인할 수 없고, 30일이 지나면 이메일·이름 같은 개인정보를 파기합니다.</p>
      <p>법령이 보존을 요구하는 거래 기록은 해당 기간 동안 분리하여 보관합니다.</p>
      <p>계속하려면 비밀번호를 다시 입력해 주세요.</p>
      <WithdrawForm />
      <p className="muted">
        <Link href="/me">내 계정으로 돌아가기</Link>
      </p>
    </div>
  );
}
