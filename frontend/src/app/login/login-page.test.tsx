import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import LoginPage from "./page";

/**
 * `?reason=` 이 아는 값이면 말하고 모르는 값이면 아무것도 안 그리는가(`D17` 「401 은 조용히 보내지 않는다」).
 *
 * **프로토타입 키가 그 「모르는 값」에 든다**(점검 8차 `I8`). 객체 색인이면 `__proto__` 가 `Object.prototype` 을 돌려줘
 * React 가 객체를 자식으로 받아 `error.tsx` 로 떨어졌다 — 주소는 사람이 고칠 수 있다.
 */

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
}));

async function renderWith(reason?: string) {
  return render(await LoginPage({ searchParams: Promise.resolve(reason === undefined ? {} : { reason }) }));
}

describe("로그인 화면의 이유 표시", () => {
  it("아는 값이면 그 문구를 status 로 말한다", async () => {
    const { container } = await renderWith("signed-up");

    expect(screen.getByRole("status")).toHaveTextContent("가입이 끝났습니다");
    await expectNoAxeViolations(container);
  });

  it.each(["__proto__", "constructor", "toString", "hasOwnProperty", "no-such-reason"])(
    "모르는 값 %s 은 아무것도 안 그린다",
    async (reason) => {
      await renderWith(reason);

      expect(screen.queryByRole("status")).toBeNull();
    },
  );
});
