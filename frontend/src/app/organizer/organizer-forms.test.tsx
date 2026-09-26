import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { PerformanceActions } from "./events/[eventId]/performance-actions";
import { PerformanceForm } from "./events/[eventId]/performance-form";
import { SettlementSummary } from "./events/[eventId]/settlement-summary";
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

  it("서버가 짚은 등급 줄을 문구로 가리킨다(`45d-b`)", async () => {
    respond(
      {
        type: "tag:projectticket.example,2026:validation-failed",
        detail: "등급 코드가 겹친다: VIP",
        errors: [{ field: "grades[1].code", message: "등급 코드가 겹친다: VIP" }],
      },
      400,
    );
    render(<EventForm organizers={[{ organizer_id: 3, name: "기획" }]} sections={["F1-A"]} />);

    await userEvent.type(screen.getByLabelText("공연 제목"), "겨울 콘서트");
    await userEvent.type(screen.getByLabelText("등급 코드(예: VIP)"), "VIP");
    await userEvent.type(screen.getByLabelText("가격(원)"), "154000");
    await userEvent.click(screen.getByRole("checkbox", { name: "F1-A" }));
    await userEvent.click(screen.getByRole("button", { name: "공연 등록" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("2번째 등급의 코드를 확인해 주세요.");
  });

  it("서버가 거절해도 친 값이 칸에 남는다(`39-4`)", async () => {
    // React 19 는 액션이 끝나면 `form.reset()` 을 부른다 — 오류를 잡고 끝나도 같다. 값을 남기는 것은 draft → `defaultValue` 다.
    respond({ type: "tag:projectticket.example,2026:validation-failed", detail: "요청 형식이 맞지 않는다" }, 400);
    render(<EventForm organizers={[{ organizer_id: 3, name: "기획" }, { organizer_id: 4, name: "둘째" }]} sections={["F1-A", "F1-B"]} />);

    await userEvent.selectOptions(screen.getByLabelText("기획사"), "4");
    await userEvent.type(screen.getByLabelText("공연 제목"), "겨울 콘서트");
    await userEvent.type(screen.getByLabelText("등급 코드(예: VIP)"), "VIP");
    await userEvent.type(screen.getByLabelText("가격(원)"), "154000");
    await userEvent.click(screen.getByRole("checkbox", { name: "F1-B" }));
    await userEvent.click(screen.getByRole("button", { name: "공연 등록" }));

    await screen.findByRole("alert");
    expect(screen.getByLabelText("기획사")).toHaveValue("4");
    expect(screen.getByLabelText("공연 제목")).toHaveValue("겨울 콘서트");
    expect(screen.getByLabelText("등급 코드(예: VIP)")).toHaveValue("VIP");
    expect(screen.getByLabelText("가격(원)")).toHaveValue(154000);
    expect(screen.getByRole("checkbox", { name: "F1-B" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "F1-A" })).not.toBeChecked();
  });

  it("접근성 위반이 없다", async () => {
    const { container } = render(<EventForm organizers={[{ organizer_id: 3, name: "기획" }]} sections={["F1-A"]} />);
    await expectNoAxeViolations(container);
  });

  it("제목 칸의 상한이 입력칸에 실제로 붙는다(`G9`)", () => {
    // `ScreenLengthTest` 는 폼이 `Field` 에 넘기는 prop 을 글자로 읽는다 — `Field` 가 `<input>` 에 안 붙이면 그 시험은 초록인 채 막는 것이 없다.
    render(<EventForm organizers={[{ organizer_id: 3, name: "기획" }]} sections={["F1-A"]} />);
    expect(screen.getByLabelText("공연 제목")).toHaveAttribute("maxlength", "200");
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

  it("판매 창을 어긴 400 은 판매 마감 기준을 말한다(`45d-b`)", async () => {
    respond(
      {
        type: "tag:projectticket.example,2026:validation-failed",
        detail: "판매 시작은 판매 마감(기본 관람 1시간 전)보다 앞이어야 한다",
        errors: [{ field: "sales_open_at", message: "판매 시작은 판매 마감(기본 관람 1시간 전)보다 앞이어야 한다" }],
      },
      400,
    );
    render(<PerformanceForm eventId={42} halls={[{ hall_id: 9, name: "1관", venue_name: "홀", sections: [] }]} />);

    await userEvent.type(screen.getByLabelText("관람 시각(한국 시간)"), "2026-10-01T19:30");
    await userEvent.type(screen.getByLabelText("판매 시작(한국 시간)"), "2026-10-01T19:00");
    await userEvent.click(screen.getByRole("button", { name: "회차 등록" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("판매 시작은 판매 마감(기본: 관람 1시간 전)보다 앞이어야 합니다.");
    expect(refresh).not.toHaveBeenCalled();
  });
});

describe("회차 조작", () => {
  it("취소는 확인을 한 번 더 받은 뒤에만 보낸다", async () => {
    const spy = respond({}, 200);
    render(<PerformanceActions performanceId={7} status="open" label="10월 1일 1관" />);

    await userEvent.click(screen.getByRole("button", { name: "회차 취소" }));
    expect(spy).not.toHaveBeenCalled();
    expect(screen.getByText(/모든 예매를 전액 환불/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "모두 환불하고 취소" }));
    await waitFor(() => expect(spy.mock.calls.some((c) => String(c[0]) === "/api/organizer/performances/7/cancel")).toBe(true));
  });

  it("오픈 전 회차는 오픈만, 닫힌 회차는 아무 버튼도 없다", () => {
    const { rerender } = render(<PerformanceActions performanceId={7} status="draft" label="10월 1일 1관" />);
    expect(screen.getByRole("button", { name: "오픈" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "회차 취소" })).toBeNull();

    rerender(<PerformanceActions performanceId={7} status="closed" label="10월 1일 1관" />);
    expect(screen.queryByRole("button")).toBeNull();
  });
});

describe("정산 칸(`45c`)", () => {
  it("상태·합계와 항목을 그리고, 정산서가 없거나 못 불러왔으면 그렇게 말한다", async () => {
    const { container, rerender } = render(
      <SettlementSummary
        settlement={{ status: "pending", amount: 90000, settle_at: "2026-10-02T00:00:00Z", settled_at: "2026-10-02T00:00:00Z", lines: [{ kind: "sale", amount: 100000 }, { kind: "platform_fee", amount: -10000 }] }}
      />,
    );
    expect(screen.getByText(/정산 계산됨 · 90,000원/)).toBeInTheDocument();
    expect(screen.getByText(/플랫폼 수수료/)).toHaveTextContent("-10,000원");
    await expectNoAxeViolations(container);

    rerender(<SettlementSummary settlement="none" />);
    expect(screen.getByText("정산서가 아직 없습니다.")).toBeInTheDocument();

    rerender(<SettlementSummary settlement="failed" />);
    expect(screen.getByText(/정산을 불러오지 못했습니다/)).toBeInTheDocument();
  });
});
