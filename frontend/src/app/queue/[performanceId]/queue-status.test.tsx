import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { POLL_MS, QueueStatus } from "./queue-status";

/**
 * 대기열 화면의 판정(`43`, `D12`). 줄에 서고(POST), 2초마다 묻고(GET), 입장하면 토큰을 이 탭에 두고 좌석으로 간다.
 * **입장 토큰이 이 화면의 계약이다** — 두는 자리(`admission:<id>`)가 선점의 헤더가 읽는 자리와 같아야 한다.
 */

const replace = vi.fn();
const push = vi.fn();
// Next 의 `useRouter` 는 같은 객체를 준다. 렌더마다 새 객체를 주면 효과가 다시 돌아 줄에 두 번 선다.
const router = { push, replace, refresh: vi.fn() };
vi.mock("next/navigation", () => ({
  useRouter: () => router,
}));

/** 부른 순서대로 답을 준다. 마지막 답은 그 뒤 호출에도 되풀이한다 */
function answers(...bodies: [unknown, number][]) {
  const spy = vi.fn();
  bodies.forEach(([body, status], i) => {
    const response = () =>
      Promise.resolve(
        status === 204 ? new Response(null, { status }) : new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }),
      );
    if (i === bodies.length - 1) spy.mockImplementation(response);
    else spy.mockImplementationOnce(response);
  });
  vi.stubGlobal("fetch", spy);
  return spy;
}

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=t";
  vi.useFakeTimers({ shouldAdvanceTime: true });
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  replace.mockClear();
  push.mockClear();
  window.sessionStorage.clear();
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

describe("대기열", () => {
  it("순번과 예상 대기를 보여 주고 차례가 오면 토큰을 두고 좌석으로 간다", async () => {
    const spy = answers(
      [{ state: "waiting", rank: 3, eta_seconds: 150 }, 200],
      [{ state: "admitted", admission_token: "tok-1" }, 200],
    );
    render(<QueueStatus performanceId="7" />);

    expect(await screen.findByRole("status")).toHaveTextContent("대기 순서 3번째 · 예상 대기 약 3분");
    expect(spy.mock.calls[0][1].method).toBe("POST");

    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_MS);
    });

    expect(spy.mock.calls[1][1].method).toBe("GET");
    expect(window.sessionStorage.getItem("admission:7")).toBe("tok-1");
    expect(replace).toHaveBeenCalledWith("/performances/7");
  });

  it("줄 나가기는 DELETE 를 보내고 토큰을 버린다", async () => {
    window.sessionStorage.setItem("admission:7", "old");
    const spy = answers([{ state: "waiting", rank: 1, eta_seconds: 10 }, 200], [null, 204]);
    render(<QueueStatus performanceId="7" />);
    await screen.findByText(/대기 순서 1번째/);

    await userEvent.click(screen.getByRole("button", { name: "줄 나가기" }));

    expect(spy.mock.calls.some((c) => c[1].method === "DELETE")).toBe(true);
    expect(window.sessionStorage.getItem("admission:7")).toBeNull();
    expect(push).toHaveBeenCalledWith("/events");
  });

  it("줄에서 빠졌으면 다시 서게 한다", async () => {
    answers([{ type: "tag:projectticket.example,2026:not-in-queue", detail: "줄에 없다" }, 404]);
    render(<QueueStatus performanceId="7" />);

    expect(await screen.findByRole("alert")).toHaveTextContent("다시 줄을 서 주세요");
    expect(screen.getByRole("button", { name: "다시 줄 서기" })).toBeInTheDocument();
  });

  it("접근성 위반이 없다", async () => {
    answers([{ state: "waiting", rank: 2, eta_seconds: 30 }, 200]);
    const { container } = render(<QueueStatus performanceId="7" />);
    await screen.findByText(/1분 이내/);

    await expectNoAxeViolations(container);
  });
});
