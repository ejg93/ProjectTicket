import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { Countdown } from "@/components/countdown";

import { CheckoutForm } from "./checkout-form";

/**
 * 예매 흐름이 쥔 판정(`D8`).
 *
 * 여기서 막는 넷: **거절을 오류로 안 그리는가**(서버가 201 로 준다), **거절 뒤에는 새 멱등키로 다시 내는가**,
 * **같은 카드로 다시 누르면 같은 멱등키인가**(`D4` — 새로 만들면 두 번 긁힌다), 그리고 카운트다운이 0 에서
 * 무엇을 말하는가.
 */

// 라우터는 App Router 가 컨텍스트로 준다. 잎사귀만 그리는 테스트에는 그 컨텍스트가 없어서 갈아 끼운다 —
// 여기서 보는 것은 이동이 아니라 **무엇을 보냈고 무엇을 그렸나**다.
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
}));

function mockFetch(body: unknown, status = 201) {
  const spy = vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }),
  );
  vi.stubGlobal("fetch", spy);
  return spy;
}

const approved = {
  payment_id: 1,
  reservation_id: 7,
  status: "approved",
  amount: 154000,
  approval_number: "A-1",
  decline_reason: null,
};

const declined = { ...approved, status: "declined", approval_number: null, decline_reason: "카드 한도" };

/**
 * 누르고, **다시 눌릴 수 있을 때까지 기다린다.**
 *
 * 제출 중에는 버튼이 `disabled` 라(`SubmitButton`) 바로 또 누르면 그 클릭이 사라진다 —
 * 기다리지 않으면 「두 번 눌렀다」를 재는 테스트가 한 번만 누르고도 통과한다.
 */
async function clickWhenReady(): Promise<void> {
  const button = screen.getByRole("button");
  await waitFor(() => expect(button).not.toBeDisabled());
  await userEvent.click(button);
  await waitFor(() => expect(button).not.toBeDisabled());
}

function keysOf(spy: ReturnType<typeof vi.fn>): string[] {
  return spy.mock.calls
    .filter((call) => String(call[0]).includes("/payments"))
    .map((call) => call[1].headers["Idempotency-Key"]);
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

describe("결제", () => {
  it("거절을 오류가 아니라 결과로 그린다", async () => {
    document.cookie = "XSRF-TOKEN=t";
    mockFetch(declined);
    render(<CheckoutForm reservationId={7} amount={154000} />);

    await userEvent.type(screen.getByLabelText("카드번호"), "4111111111111111");
    await userEvent.click(screen.getByRole("button"));

    // 서버가 201 로 준 것이라 「서버가 고장났다」가 아니라 「카드사가 거절했다」다.
    expect(await screen.findByRole("alert")).toHaveTextContent(/거절했습니다/);
  });

  it("같은 카드로 다시 누르면 같은 멱등키를 보낸다", async () => {
    document.cookie = "XSRF-TOKEN=t";
    const spy = mockFetch(approved);
    render(<CheckoutForm reservationId={7} amount={154000} />);

    // **React 는 액션이 끝나면 폼을 비운다.** 그래서 다시 낼 때 사용자는 같은 번호를 다시 친다 —
    // 테스트도 그 모양으로 밟는다(안 치면 `required` 가 제출을 막아 클릭이 사라진다).
    const field = screen.getByLabelText("카드번호");
    await userEvent.type(field, "4111111111111111");
    await clickWhenReady();
    // **React 는 액션이 끝나면 폼을 비운다.** 다시 낼 때 사용자는 같은 번호를 다시 치고, 그것이 같은 요청이다.
    await userEvent.clear(field);
    await userEvent.type(field, "4111111111111111");
    await clickWhenReady();

    // 새로 만들면 서버가 재전송이 아니라 새 결제로 본다 — 두 번 긁힌다(`D4`).
    const keys = keysOf(spy);
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
  });

  it("카드를 바꾸면 새 멱등키를 보낸다", async () => {
    document.cookie = "XSRF-TOKEN=t";
    // **`approved` 로 잰다.** `declined` 면 화면이 어차피 키를 버려서, 카드 비교를 통째로 지워도 이 테스트가
    // 초록이다 — 물지 않는 테스트가 된다(마무리 9차 독립 리뷰).
    const spy = mockFetch(approved);
    render(<CheckoutForm reservationId={7} amount={154000} />);
    const field = screen.getByLabelText("카드번호");

    await userEvent.type(field, "4111111111111111");
    await clickWhenReady();
    await userEvent.clear(field);
    await userEvent.type(field, "4222222222222222");
    await clickWhenReady();

    // 다른 카드는 **다른 요청**이다. 같은 키로 보내면 서버가 앞 시도의 답을 돌려준다.
    const keys = keysOf(spy);
    expect(keys).toHaveLength(2);
    expect(keys[0]).not.toBe(keys[1]);
  });
});

describe("카운트다운", () => {
  it("남은 시간을 분초로 그린다", async () => {
    render(<Countdown deadline={new Date(Date.now() + 90_000).toISOString()} label="남은 시간" />);

    expect(await screen.findByRole("timer")).toHaveTextContent(/01:(29|30)/);
  });

  it("0 이면 다시 고르라고 말한다", async () => {
    render(<Countdown deadline={new Date(Date.now() - 1000).toISOString()} label="남은 시간" />);

    // 화면이 선점을 푸는 것이 아니다 — 푸는 것은 만료 배치고, 여기는 서두를 이유를 보여주는 자리다.
    expect(await screen.findByRole("alert")).toHaveTextContent(/좌석을 다시 골라 주세요/);
  });
});
