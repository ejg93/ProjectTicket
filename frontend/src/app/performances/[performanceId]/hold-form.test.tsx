import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { SeatMapData } from "@/components/seat-map";

import { HoldForm } from "./hold-form";

/**
 * 선점이 쥔 판정(`D8`·`D4`).
 *
 * **멱등키가 이 컴포넌트의 계약이다**(`42`). 같은 좌석으로 다시 누르면 같은 키, 좌석을 바꾸면 새 키 —
 * 앞을 어기면 응답을 못 받은 첫 요청의 선점이 남아 둘째가 `duplicate-hold` 로 막히고,
 * 뒤를 어기면 서버가 **앞 요청의 답**(다른 좌석의 예매)을 돌려준다.
 */

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
}));

function seatMap(): SeatMapData {
  return {
    version: 1,
    grades: [{ code: "VIP", name: "VIP", price: 154000 }],
    sections: [
      {
        code: "F1-A",
        name: "1층 A구역",
        grade: "VIP",
        rows: [
          {
            label: "A",
            seats: [
              { id: 11, n: 1, s: "A" },
              { id: 12, n: 2, s: "A" },
            ],
          },
        ],
      },
    ],
  };
}

function mockFetch(body: unknown, status = 201) {
  const spy = vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }),
  );
  vi.stubGlobal("fetch", spy);
  return spy;
}

function keysOf(spy: ReturnType<typeof vi.fn>): string[] {
  return spy.mock.calls
    .filter((call) => String(call[0]).includes("/reservations"))
    .map((call) => call[1].headers["Idempotency-Key"]);
}

async function submit(): Promise<void> {
  const button = screen.getByRole("button", { name: /예매/ });
  await waitFor(() => expect(button).not.toBeDisabled());
  await userEvent.click(button);
  await waitFor(() => expect(button).not.toBeDisabled());
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

describe("선점", () => {
  it("좌석을 안 고르면 서버를 안 부른다", async () => {
    document.cookie = "XSRF-TOKEN=t";
    const spy = mockFetch({ reservation_id: 1 });
    render(<HoldForm data={seatMap()} performanceId="5" />);

    await submit();

    expect(keysOf(spy)).toHaveLength(0);
    expect(screen.getByRole("alert")).toHaveTextContent(/좌석을 먼저 골라 주세요/);
  });

  it("같은 좌석으로 다시 누르면 같은 멱등키를 보낸다", async () => {
    document.cookie = "XSRF-TOKEN=t";
    const spy = mockFetch({ reservation_id: 1 });
    render(<HoldForm data={seatMap()} performanceId="5" />);

    await userEvent.click(screen.getByRole("checkbox", { name: /1번, 선택 가능/ }));
    await submit();
    await submit();

    const keys = keysOf(spy);
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
  });

  it("좌석을 바꾸면 새 멱등키를 보낸다", async () => {
    document.cookie = "XSRF-TOKEN=t";
    const spy = mockFetch({ reservation_id: 1 });
    render(<HoldForm data={seatMap()} performanceId="5" />);

    await userEvent.click(screen.getByRole("checkbox", { name: /1번, 선택 가능/ }));
    await submit();
    await userEvent.click(screen.getByRole("checkbox", { name: /2번, 선택 가능/ }));
    await submit();

    const keys = keysOf(spy);
    expect(keys).toHaveLength(2);
    expect(keys[0]).not.toBe(keys[1]);
  });

  it("남이 먼저 잡은 좌석은 새로 고치라고 말한다", async () => {
    document.cookie = "XSRF-TOKEN=t";
    mockFetch(
      { type: "tag:projectticket.example,2026:seat-taken", detail: "이미 잡힌 좌석이 있다" },
      409,
    );
    render(<HoldForm data={seatMap()} performanceId="5" />);

    await userEvent.click(screen.getByRole("checkbox", { name: /1번, 선택 가능/ }));
    await submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(/좌석 현황을 새로 고쳐/);
  });
});
