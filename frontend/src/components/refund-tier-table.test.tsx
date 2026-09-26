import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { RefundTierTable } from "./refund-tier-table";

/**
 * 결제 전 구간표(`42-1b`, 약관 제3조)가 서버 판을 **빈틈없이** 옮기는가 — 구간 사이의 날이 비지 않고, 가장 늦은 구간 뒤는 「취소할 수 없다」다.
 */
const SEED = {
  effective_at: "2026-01-01T00:00:00Z",
  tiers: [
    { days_before_min: 10, rate: 0 },
    { days_before_min: 7, rate: 0.1 },
    { days_before_min: 3, rate: 0.2 },
    { days_before_min: 1, rate: 0.3 },
  ],
};

describe("취소 수수료 구간표", () => {
  it("시작 판(`V10`)을 날 구간과 율로 그리고 당일은 취소할 수 없다고 말한다", async () => {
    const { container } = render(<RefundTierTable table={SEED} />);

    const rows = screen.getAllByRole("row").slice(1).map((row) => row.textContent);
    expect(rows).toEqual(["관람 10일 전까지0%", "관람 9~7일 전10%", "관람 6~3일 전20%", "관람 2~1일 전30%"]);
    expect(screen.getByText("관람 당일은 취소할 수 없습니다.")).toBeInTheDocument();
    expect(screen.getByText("2026-01-01 시행")).toBeInTheDocument();
    await expectNoAxeViolations(container);
  });

  it("당일 구간이 있으면 취소 불가 문구가 없다", () => {
    render(<RefundTierTable table={{ effective_at: SEED.effective_at, tiers: [{ days_before_min: 1, rate: 0.3 }, { days_before_min: 0, rate: 0.5 }] }} />);

    expect(screen.getByRole("rowheader", { name: "관람 당일" })).toBeInTheDocument();
    expect(screen.queryByText(/취소할 수 없습니다/)).toBeNull();
  });

  it("못 불러왔으면 그 칸만 말한다", () => {
    render(<RefundTierTable table="failed" />);

    expect(screen.getByText("수수료 구간표를 불러오지 못했습니다.")).toBeInTheDocument();
    expect(screen.queryByRole("table")).toBeNull();
  });
});
