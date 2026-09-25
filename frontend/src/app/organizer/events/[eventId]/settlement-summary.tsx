import { money } from "@/lib/format";

/** `GET /api/organizer/settlements?performanceId=`(`45c`). 서버 JSON 이 snake_case 라 **받은 그대로 적는다**(`D5`) */
export type Settlement = {
  status: string;
  amount: number;
  settle_at: string;
  settled_at: string | null;
  lines: { kind: string; amount: number }[];
};

const STATUS: Record<string, string> = {
  scheduled: "정산 예정",
  pending: "정산 계산됨",
  confirmed: "정산 확정",
  paid: "지급 완료",
};

const KIND: Record<string, string> = {
  sale: "판매",
  platform_fee: "플랫폼 수수료",
  cancel_fee: "취소 수수료",
  adjustment: "조정",
};

/**
 * 회차 하나의 정산 칸(`45c`). 따로 화면을 안 만든다 — 숫자 몇 개라 공연 화면 안의 한 칸이다(설계).
 * 정산서가 아직 없으면(`404`) 페이지가 `null` 을 넘긴다. 합계는 항목의 합이다(서버의 지연 제약).
 */
export function SettlementSummary({ settlement }: { settlement: Settlement | null }) {
  if (!settlement) {
    return <p className="muted">정산서가 아직 없습니다.</p>;
  }
  return (
    <details>
      <summary>
        {STATUS[settlement.status] ?? "확인 중"} · {money(settlement.amount)}
      </summary>
      <ul>
        {settlement.lines.map((line) => (
          <li key={line.kind}>
            {KIND[line.kind] ?? line.kind} {money(line.amount)}
          </li>
        ))}
      </ul>
    </details>
  );
}
