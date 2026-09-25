import type { Metadata } from "next";

import { ConsentBody, type ConsentItemDetail } from "@/components/consent-body";
import { apiPublic } from "@/lib/api";
import { date } from "@/lib/format";

export const metadata: Metadata = {
  title: "이용약관 · ProjectTicket",
};

/**
 * 이용약관(`39-1`). **서버가 든 지금 판을 그린다** — 화면에 박으면 개정할 때 두 곳이 갈리고, 가입 때 동의받은 판과 다른 글이 보인다.
 * 공개 읽기라 {@link apiPublic} 이다 — 발(footer)에서 누구나 닿는다.
 */
export default async function TermsPage() {
  const item = await apiPublic<ConsentItemDetail>("/api/consent-items/terms_of_service");

  return (
    <article>
      <h1>{item.title}</h1>
      <p className="muted">시행일 {date(item.effective_at)}</p>
      {item.body ? <ConsentBody body={item.body} /> : null}
    </article>
  );
}
