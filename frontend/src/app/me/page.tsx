import type { Metadata } from "next";
import Link from "next/link";

import { apiSession } from "@/lib/api-session";

export const metadata: Metadata = {
  title: "내 계정 · ProjectTicket",
};

/** 서버 JSON 이 snake_case 라 **받은 그대로 적는다**(`D5`) */
type MeResponse = {
  account_id: number;
  email: string;
  display_name: string;
  role: string;
};

/**
 * 내 계정.
 *
 * **로그인 여부를 여기서 안 따진다.** 세션이 없거나 끊겼으면 {@link apiSession} 이 로그인 화면으로
 * 보낸다 — 화면마다 따지면 한 화면이 빠뜨렸을 때 그 화면만 조용히 빈 값을 그린다(`D16`).
 *
 * **역할 이름을 그대로 안 보인다**(`D17` 「내부 값을 그대로 안 보인다」). 무엇을 할 수 있는지는
 * 역할이 아니라 화면 지도가 정한다 — 지금은 이름만 그린다.
 */
export default async function MePage() {
  const me = await apiSession<MeResponse>("/api/me");

  return (
    <div>
      <h1>내 계정</h1>
      <dl>
        <dt>이름</dt>
        <dd>{me.display_name}</dd>
        <dt>이메일</dt>
        <dd>{me.email}</dd>
      </dl>
      <p>
        <Link href="/me/reservations">내 예매</Link> · <Link href="/me/withdraw">회원 탈퇴</Link>
      </p>
    </div>
  );
}
