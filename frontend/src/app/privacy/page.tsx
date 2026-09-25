import type { Metadata } from "next";

import type { ConsentItemDetail } from "@/components/consent-body";
import { apiPublic } from "@/lib/api";
import { date } from "@/lib/format";

export const metadata: Metadata = {
  title: "개인정보 수집·이용 안내 · ProjectTicket",
};

/**
 * 개인정보 수집·이용 안내(`39-1`). 수집 항목의 **정형 넷**을 그린다 — 개인정보 보호법 제15조 제2항이 고지할 넷을 정해서
 * 서버가 본문 대신 칸으로 든다(`V3`). 없는 칸은 줄을 안 그린다(`D17` 「없는 값」).
 * **처리방침(제30조 — 파기·위탁·제3자 제공·보호책임자)이 아니다** — 그 이름을 달면 사실이 아닌 것을 말한다. 처리방침은 `39-3`.
 */
export default async function PrivacyPage() {
  const item = await apiPublic<ConsentItemDetail>("/api/consent-items/privacy_collect");

  const rows: [string, string | null][] = [
    ["수집·이용 목적", item.purpose],
    ["수집 항목", item.collected_items],
    ["보유·이용 기간", item.retention_period],
    ["동의를 거부할 권리와 불이익", item.refusal_disadvantage],
  ];

  return (
    <article>
      <h1>개인정보 수집·이용 안내</h1>
      <p className="muted">시행일 {date(item.effective_at)}</p>
      <h2>{item.title}</h2>
      <dl>
        {rows.flatMap(([label, value]) =>
          value ? [<dt key={`${label}-t`}>{label}</dt>, <dd key={`${label}-d`}>{value}</dd>] : [],
        )}
      </dl>
    </article>
  );
}
