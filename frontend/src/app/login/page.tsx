import type { Metadata } from "next";
import Link from "next/link";

import { LoginForm } from "./login-form";

export const metadata: Metadata = {
  title: "로그인 · ProjectTicket",
};

/**
 * 왜 이 화면으로 왔는지. 보낸 자리는 `api.ts`(클라이언트)와 `api-session.ts`(서버)다.
 *
 * **모르는 값은 아무것도 안 그린다.** 주소는 사람이 고칠 수 있어서 여기 없는 값이 올 수 있고,
 * 그때 「로그인이 만료됐다」를 그리면 사실이 아닌 것을 말하게 된다.
 */
const REASON_TEXT: Record<string, string> = {
  "session-expired": "로그인이 만료되어 다시 로그인이 필요합니다.",
  "login-required": "이 화면은 로그인하신 뒤에 이용하실 수 있습니다.",
  "signed-up": "가입이 끝났습니다. 이제 로그인해 주세요.",
};

/**
 * 로그인 화면.
 *
 * 서버 컴포넌트다. 움직이는 것은 {@link LoginForm} 하나뿐이라 그것만 클라이언트로 뗐다
 * (`D16` 「경계를 잎사귀로 내린다」).
 */
export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ reason?: string }>;
}) {
  const { reason } = await searchParams;
  const notice = REASON_TEXT[reason ?? ""];

  return (
    <div>
      <h1>로그인</h1>

      {/*
        왜 여기로 왔는지를 말한다(`D17` 「401 은 조용히 보내지 않는다」). 설명 없이 로그인 화면이
        나오면 사용자는 자기가 뭘 잘못 눌렀다고 생각한다.
      */}
      {notice ? <p role="status">{notice}</p> : null}

      <LoginForm />

      <p className="muted">
        계정이 없으시면 <Link href="/signup">회원가입</Link>으로 가 주세요.
      </p>
    </div>
  );
}
