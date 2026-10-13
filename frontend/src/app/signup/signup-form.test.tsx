import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

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
    const { container } = render(<SignupForm items={ITEMS} />);

    await userEvent.type(screen.getByLabelText("이메일"), "fan@example.com");
    await userEvent.type(screen.getByLabelText("이름"), "관객");
    await userEvent.type(screen.getByLabelText("비밀번호"), "correct horse battery staple");
    await userEvent.click(screen.getByRole("checkbox", { name: /이용약관/ }));
    await userEvent.click(screen.getByRole("button", { name: "가입" }));

    await screen.findByRole("alert");
    await expectNoAxeViolations(container);
    expect(screen.getByLabelText("이메일")).toHaveValue("fan@example.com");
    expect(screen.getByLabelText("이름")).toHaveValue("관객");
    expect(screen.getByLabelText("비밀번호")).toHaveValue("");
    expect(screen.getByRole("checkbox", { name: /이용약관/ })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: /마케팅 수신/ })).not.toBeChecked();
  });

  it("짧은 비밀번호는 서버에 안 보내고 그 칸만 지목한다(`I8-2`)", async () => {
    const spy = vi.fn();
    vi.stubGlobal("fetch", spy);
    const { container } = render(<SignupForm items={ITEMS} />);

    await userEvent.type(screen.getByLabelText("이메일"), "fan@example.com");
    await userEvent.type(screen.getByLabelText("이름"), "관객");
    await userEvent.type(screen.getByLabelText("비밀번호"), "short");
    await userEvent.click(screen.getByRole("checkbox", { name: /이용약관/ }));
    await userEvent.click(screen.getByRole("button", { name: "가입" }));

    // 칸 아래 인라인 + `aria-invalid` — 낭독기가 칸에 들어갈 때 이유를 같이 읽는다(`D17`)
    const password = await screen.findByLabelText("비밀번호");
    await expect.poll(() => password.getAttribute("aria-invalid")).toBe("true");
    expect(password).toHaveAccessibleDescription("비밀번호는 15자 이상이어야 합니다.");
    expect(screen.getByLabelText("이메일")).not.toHaveAttribute("aria-invalid");
    expect(screen.queryByRole("alert")).toBeNull();
    expect(spy).not.toHaveBeenCalled();
    await expectNoAxeViolations(container);
  });

  it("서버가 짚은 칸에 서버 문구가 아니라 화면 문구를 단다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            type: "tag:projectticket.example,2026:validation-failed",
            detail: "요청 형식이 맞지 않는다",
            errors: [{ field: "password", message: "흔한 비밀번호다" }],
          }),
          { status: 400, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );
    render(<SignupForm items={ITEMS} />);

    await userEvent.type(screen.getByLabelText("이메일"), "fan@example.com");
    await userEvent.type(screen.getByLabelText("이름"), "관객");
    await userEvent.type(screen.getByLabelText("비밀번호"), "password password password");
    await userEvent.click(screen.getByRole("checkbox", { name: /이용약관/ }));
    await userEvent.click(screen.getByRole("button", { name: "가입" }));

    const password = screen.getByLabelText("비밀번호");
    await expect.poll(() => password.getAttribute("aria-invalid")).toBe("true");
    expect(password).toHaveAccessibleDescription(/흔히 쓰는 비밀번호는 쓸 수 없습니다/);
    expect(screen.queryByText("흔한 비밀번호다")).toBeNull();
    // 칸을 전부 지목했으니 폼 전체 문구는 없다 — 남은 것만 거기로 간다
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
