import { date } from "@/lib/format";

/** `GET /api/refund-tiers`(`42-1a`). 서버 JSON 이 snake_case 라 **받은 그대로 적는다**(`D5`). 구간은 큰 것부터 온다 */
export type RefundTiers = {
  effective_at: string;
  tiers: { days_before_min: number; rate: number }[];
};

/** 구간 하나의 시점 문구. 앞 구간(더 이른 시점)의 시작 하루 전까지가 이 구간이다 */
function spanOf(min: number, previousMin: number | null): string {
  if (previousMin == null) return `관람 ${min}일 전까지`;
  const upper = previousMin - 1;
  if (min === 0) return upper === 0 ? "관람 당일" : `관람 ${upper}일 전 ~ 당일`;
  return upper === min ? `관람 ${min}일 전` : `관람 ${upper}~${min}일 전`;
}

/** 가장 늦은 구간 뒤로는 구간이 없다 — 취소할 수 없다(`D6` 「0 행이 없는 것이 당일 취소 불가다」) */
function closedFrom(lastMin: number): string | null {
  if (lastMin === 0) return null;
  return lastMin === 1 ? "관람 당일은 취소할 수 없습니다." : `관람 ${lastMin - 1}일 전부터는 취소할 수 없습니다.`;
}

/**
 * 취소 수수료 구간표 — **결제 전에 보여 준다**(`42-1b`, 약관 제3조 · `D6` 고지). 판은 취소 계산과 같은 것이다(`RefundQuote`).
 * 못 불러왔으면 그 칸만 말한다 — 곁가지 칸 하나가 결제를 막으면 안 된다.
 */
export function RefundTierTable({ table }: { table: RefundTiers | "failed" }) {
  if (table === "failed") {
    return <p className="muted">수수료 구간표를 불러오지 못했습니다.</p>;
  }

  const last = table.tiers.at(-1);
  const closed = last ? closedFrom(last.days_before_min) : null;

  return (
    <section aria-labelledby="refund-tiers-title">
      <h2 id="refund-tiers-title">취소 수수료</h2>
      <table>
        <caption className="muted">{date(table.effective_at)} 시행</caption>
        <thead>
          <tr>
            <th scope="col">취소 시점</th>
            <th scope="col">수수료</th>
          </tr>
        </thead>
        <tbody>
          {table.tiers.map((tier, i) => (
            <tr key={tier.days_before_min}>
              <th scope="row">{spanOf(tier.days_before_min, i === 0 ? null : table.tiers[i - 1].days_before_min)}</th>
              <td>{Math.round(tier.rate * 100)}%</td>
            </tr>
          ))}
        </tbody>
      </table>
      {closed ? <p>{closed}</p> : null}
    </section>
  );
}
