import type { Metadata } from "next";

import { apiPublic } from "@/lib/api";

import { SignupForm, type ConsentItem } from "./signup-form";

export const metadata: Metadata = {
  title: "회원가입 · ProjectTicket",
};

/**
 * 가입 화면.
 *
 * **동의 항목을 서버에서 받아 그린다.** 목록을 화면에 박으면 항목이 늘거나 필수 여부가 바뀔 때
 * 두 곳을 고쳐야 하고, 한쪽만 고치면 가입이 `required-consent-missing` 으로 막힌다.
 *
 * 읽기라 {@link apiPublic} 다 — 로그인 전 화면이라 실을 세션도 없다.
 */
export default async function SignupPage() {
  const items = await apiPublic<ConsentItem[]>("/api/consent-items");

  return (
    <div>
      <h1>회원가입</h1>
      <SignupForm items={items} />
    </div>
  );
}
