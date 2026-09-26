import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { SignupForm, type ConsentItem } from "./signup-form";

/**
 * 가입이 거절돼도 친 값이 남는가(`39-4`). React 19 는 액션이 끝나면 폼을 비운다 — 오류를 잡고 끝나도 같다.
 * 비밀번호는 **안 남긴다** — 서버가 `toString` 에서도 빼는 값이다(`D9`).
 */

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
}));

const ITEMS: ConsentItem[] = [
  { consent_item_id: 1, code: "terms", title: "이용약관", required: true, sort_no: 1 },
  { consent_item_id: 2, code: "marketing", title: "마케팅 수신", required: false, sort_no: 2 },
];

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=t";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

describe("가입", () => {
  it("서버가 거절해도 이메일·이름·동의는 남고 비밀번호는 비워진다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ type: "tag:projectticket.example,2026:email-taken", detail: "가입된 이메일이다" }), {
          status: 409,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );
    render(<SignupForm items={ITEMS} />);

    await userEvent.type(screen.getByLabelText("이메일"), "fan@example.com");
    await userEvent.type(screen.getByLabelText("이름"), "관객");
    await userEvent.type(screen.getByLabelText("비밀번호"), "correct horse battery staple");
    await userEvent.click(screen.getByRole("checkbox", { name: /이용약관/ }));
    await userEvent.click(screen.getByRole("button", { name: "가입" }));

    await screen.findByRole("alert");
    expect(screen.getByLabelText("이메일")).toHaveValue("fan@example.com");
    expect(screen.getByLabelText("이름")).toHaveValue("관객");
    expect(screen.getByLabelText("비밀번호")).toHaveValue("");
    expect(screen.getByRole("checkbox", { name: /이용약관/ })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: /마케팅 수신/ })).not.toBeChecked();
  });
});
