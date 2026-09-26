import type { Metadata } from "next";

import { apiPublic } from "@/lib/api";

import { PolicyView, type PolicyDocument } from "./policy-view";

export const metadata: Metadata = {
  title: "개인정보처리방침 · ProjectTicket",
};

/**
 * 개인정보처리방침(`39-3b`, 개인정보 보호법 제30조). **서버가 든 지금 판을 그린다** — 개정은 새 판을 넣는 것이다(`V21`).
 * 제30조 제2항이 「쉽게 확인할 수 있도록」 공개하라고 해서 발(footer)에서 누구나 닿는다 — 공개 읽기라 {@link apiPublic} 이다.
 * 수집·이용 동의 고지(제15조)는 `/privacy` 가 따로 그린다 — 동의받는 것과 공개하는 것이 다르다.
 */
export default async function PrivacyPolicyPage() {
  const policy = await apiPublic<PolicyDocument>("/api/policies/privacy_policy");
  return <PolicyView policy={policy} />;
}
