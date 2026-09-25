import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { PerformanceActions } from "./events/[eventId]/performance-actions";
import { PerformanceForm } from "./events/[eventId]/performance-form";
import { EventForm } from "./events/new/event-form";

/**
 * 기획사 폼 셋(`45b`) — 공연 등록이 등급 줄을 본문으로 모으는가, 회차 등록이 KST 오프셋을 붙이는가,
 * 회차 취소가 두 단계인가(모든 예매를 환불한다).
 */

const push = vi.fn();
const refresh = vi.fn();
const router = { push, replace: vi.fn(), refresh };
vi.mock("next/navigation", () => ({
  useRouter: () => router,
}));

function respond(body: unknown, status = 201) {
  const spy = vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }),
  );
  vi.stubGlobal("fetch", spy);
  return spy;
}

const bodyOf = (spy: ReturnType<typeof vi.fn>, path: string) =>
  JSON.parse(spy.mock.calls.find((c) => String(c[0]) === path)?.[1].body);

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=t";
});

afterEach(() => {
  vi.unstubAllGlobals();
  push.mockClear();
  refresh.mockClear();
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

describe("공연 등록", () => {
  it("등급 줄을 코드·가격·구역으로 모아 보내고 그 공연 화면으로 간다", async () => {
    const spy = respond({ event_id: 42 });
    render(<EventForm organizers={[{ organizer_id: 3, name: "기획" }]} sections={["F1-A", "F1-B"]} />);

    await userEvent.type(screen.getByLabelText("공연 제목"), "겨울 콘서트");
    await userEvent.type(screen.getByLabelText("등급 코드(예: VIP)"), "VIP");
    await userEvent.type(screen.getByLabelText("가격(원)"), "154000");
    await userEvent.click(screen.getByRole("checkbox", { name: "F1-A" }));
    await userEvent.click(screen.getByRole("button", { name: "공연 등록" }));

    await waitFor(() => expect(push).toHaveBeenCalledWith("/organizer/events/42"));
    expect(bodyOf(spy, "/api/organizer/events")).toEqual({
      organizer_id: 3,
      title: "겨울 콘서트",
      grades: [{ code: "VIP", price: 154000, sections: ["F1-A"] }],
    });
  });

  it("접근성 위반이 없다", async () => {
    const { container } = render(<EventForm organizers={[{ organizer_id: 3, name: "기획" }]} sections={["F1-A"]} />);
    await expectNoAxeViolations(container);
  });
});

describe("회차 등록", () => {
  it("시각에 KST 오프셋을 붙여 보낸다", async () => {
    const spy = respond({ performance_id: 7 });
    render(<PerformanceForm eventId={42} halls={[{ hall_id: 9, name: "1관", venue_name: "홀", sections: [] }]} />);

    await userEvent.type(screen.getByLabelText("관람 시각(한국 시간)"), "2026-10-01T19:30");
    await userEvent.type(screen.getByLabelText("판매 시작(한국 시간)"), "2026-09-20T10:00");
    await userEvent.click(screen.getByRole("button", { name: "회차 등록" }));

    await waitFor(() => expect(refresh).toHaveBeenCalled());
    expect(bodyOf(spy, "/api/organizer/events/42/performances")).toEqual({
      hall_id: 9,
      starts_at: "2026-10-01T19:30:00+09:00",
      sales_open_at: "2026-09-20T10:00:00+09:00",
    });
  });
});

describe("회차 조작", () => {
  it("취소는 확인을 한 번 더 받은 뒤에만 보낸다", async () => {
    const spy = respond({}, 200);
    render(<PerformanceActions performanceId={7} status="open" />);

    await userEvent.click(screen.getByRole("button", { name: "회차 취소" }));
    expect(spy).not.toHaveBeenCalled();
    expect(screen.getByText(/모든 예매를 전액 환불/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "모두 환불하고 취소" }));
    await waitFor(() => expect(spy.mock.calls.some((c) => String(c[0]) === "/api/organizer/performances/7/cancel")).toBe(true));
  });

  it("오픈 전 회차는 오픈만, 닫힌 회차는 아무 버튼도 없다", () => {
    const { rerender } = render(<PerformanceActions performanceId={7} status="draft" />);
    expect(screen.getByRole("button", { name: "오픈" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "회차 취소" })).toBeNull();

    rerender(<PerformanceActions performanceId={7} status="closed" />);
    expect(screen.queryByRole("button")).toBeNull();
  });
});
