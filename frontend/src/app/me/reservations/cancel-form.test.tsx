import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { CancelForm } from "./cancel-form";
import { ReservationList, type MyReservation } from "./reservation-list";

/**
 * 취소가 **두 단계**인가(`44b`, `D6` 고지) — 미리보기 액수가 보인 뒤에만 취소 버튼이 살고, 취소할 수 없으면 사유만 보인다.
 */

const refresh = vi.fn();
const router = { push: vi.fn(), replace: vi.fn(), refresh };
vi.mock("next/navigation", () => ({
  useRouter: () => router,
}));

function answers(...bodies: [unknown, number][]) {
  const spy = vi.fn();
  for (const [body, status] of bodies) {
    spy.mockImplementationOnce(() =>
      Promise.resolve(new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } })),
    );
  }
  vi.stubGlobal("fetch", spy);
  return spy;
}

const PREVIEW = {
  cancellable: true,
  reason: null,
  payment_amount: 308000,
  days_before: 8,
  tier_rate: 0.1,
  fee_amount: 30800,
  refund_amount: 277200,
};

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=t";
});

afterEach(() => {
  vi.unstubAllGlobals();
  refresh.mockClear();
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

describe("예매 취소", () => {
  it("미리보기 액수가 보인 뒤에만 취소를 보낸다", async () => {
    const spy = answers([PREVIEW, 200], [{ status: "done" }, 200]);
    render(<CancelForm reservationId={9} />);

    // 첫 누름은 미리보기만 받는다 — 취소를 안 보낸다.
    expect(screen.queryByRole("button", { name: "이 금액으로 취소" })).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "취소" }));

    expect(await screen.findByText("277,200원")).toBeInTheDocument();
    expect(screen.getByText(/30,800원/)).toHaveTextContent("(10%)");
    expect(spy.mock.calls.some((c) => String(c[0]).endsWith("/cancel"))).toBe(false);

    await userEvent.click(screen.getByRole("button", { name: "이 금액으로 취소" }));

    await waitFor(() => expect(spy.mock.calls.some((c) => String(c[0]).endsWith("/cancel") && c[1].method === "POST")).toBe(true));
    expect(await screen.findByRole("status")).toHaveTextContent("취소했습니다");
    expect(refresh).toHaveBeenCalled();
  });

  it("본 금액을 싣고, 그사이 바뀌었으면(409) 미리보기를 다시 받아 새 금액을 보여 준다(`42-1b`)", async () => {
    const changed = { type: "tag:projectticket.example,2026:quote-changed", status: 409, detail: "본 금액과 지금 금액이 다르다", refund_amount: 261800 };
    const spy = answers([PREVIEW, 200], [changed, 409], [{ ...PREVIEW, tier_rate: 0.15, fee_amount: 46200, refund_amount: 261800 }, 200]);
    render(<CancelForm reservationId={9} />);

    await userEvent.click(screen.getByRole("button", { name: "취소" }));
    await userEvent.click(await screen.findByRole("button", { name: "이 금액으로 취소" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("환불 금액이 바뀌었습니다. 다시 확인해 주세요.");
    expect(await screen.findByText("261,800원")).toBeInTheDocument();
    const posted = spy.mock.calls.find((c) => String(c[0]).endsWith("/cancel"));
    expect(JSON.parse(posted?.[1].body)).toEqual({ refund_amount: 277200 });
    // 미리보기 → 취소(409) → 미리보기. 409 뒤에 취소를 스스로 다시 보내지 않는다 — 새 금액을 사람이 보고 누른다.
    expect(spy.mock.calls.map((c) => String(c[0]).split("/").at(-1))).toEqual(["refund-preview", "cancel", "refund-preview"]);
    expect(refresh).not.toHaveBeenCalled();
  });

  it("409 뒤 새 미리보기를 못 받으면 옛 금액을 걷고 그 실패를 말한다", async () => {
    const changed = { type: "tag:projectticket.example,2026:quote-changed", status: 409, detail: "본 금액과 지금 금액이 다르다", refund_amount: 261800 };
    answers([PREVIEW, 200], [changed, 409], [{ type: "about:blank", status: 502 }, 502]);
    render(<CancelForm reservationId={9} />);

    await userEvent.click(screen.getByRole("button", { name: "취소" }));
    await userEvent.click(await screen.findByRole("button", { name: "이 금액으로 취소" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("취소하지 못했습니다");
    expect(screen.queryByRole("button", { name: "이 금액으로 취소" })).toBeNull();
    expect(screen.queryByText("277,200원")).toBeNull();
  });

  it("취소할 수 없으면 사유만 보이고 취소 버튼이 없다", async () => {
    answers([{ ...PREVIEW, cancellable: false, reason: "cancel-window-closed", fee_amount: null, refund_amount: null }, 200]);
    render(<CancelForm reservationId={9} />);

    await userEvent.click(screen.getByRole("button", { name: "취소" }));

    expect(await screen.findByRole("status")).toHaveTextContent("관람일 당일이거나 지난 공연은 취소할 수 없습니다");
    expect(screen.queryByRole("button", { name: "이 금액으로 취소" })).toBeNull();
  });

  it("목록에 접근성 위반이 없고 취소는 예매 완료에만 있다", async () => {
    const items: MyReservation[] = [
      { reservation_id: 1, performance_id: 5, event_title: "겨울 콘서트", starts_at: "2026-10-01T10:30:00Z", status: "reserved", total_amount: 154000, seat_count: 1, created_at: "2026-09-20T01:00:00Z" },
      { reservation_id: 2, performance_id: 6, event_title: "봄 콘서트", starts_at: "2026-11-01T10:30:00Z", status: "expired", total_amount: 50000, seat_count: 1, created_at: "2026-09-19T01:00:00Z" },
    ];
    const { container } = render(<ReservationList items={items} />);

    expect(screen.getAllByRole("button", { name: "취소" })).toHaveLength(1);
    expect(screen.getByText(/선점 시간 만료/)).toBeInTheDocument();
    await expectNoAxeViolations(container);
  });
});
