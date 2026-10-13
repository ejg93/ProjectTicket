import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { LoginForm } from "./login-form";

/**
 * 로그인이 거절돼도 이메일은 남고 비밀번호는 비워지는가(`39-4`). React 19 는 액션이 끝나면 폼을 비운다.
 */

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
}));

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=t";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

describe("로그인", () => {
  it("비밀번호가 틀려도 이메일은 남고 비밀번호는 비워진다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ type: "tag:projectticket.example,2026:login-failed", detail: "로그인하지 못했다" }), {
          status: 401,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );
    const { container } = render(<LoginForm />);

    await userEvent.type(screen.getByLabelText("이메일"), "fan@example.com");
    await userEvent.type(screen.getByLabelText("비밀번호"), "wrong password here");
    await userEvent.click(screen.getByRole("button", { name: "로그인" }));

    await screen.findByRole("alert");
    await expectNoAxeViolations(container);
    expect(screen.getByLabelText("이메일")).toHaveValue("fan@example.com");
    expect(screen.getByLabelText("비밀번호")).toHaveValue("");
  });

  it("로그인 실패는 어느 칸도 지목하지 않는다(`I8-2`)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ type: "tag:projectticket.example,2026:login-failed", detail: "로그인하지 못했다" }), {
          status: 401,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );
    const { container } = render(<LoginForm />);

    await userEvent.type(screen.getByLabelText("이메일"), "fan@example.com");
    await userEvent.type(screen.getByLabelText("비밀번호"), "wrong password here");
    await userEvent.click(screen.getByRole("button", { name: "로그인" }));

    // 이메일이 틀렸는지 비밀번호가 틀렸는지를 서버가 안 말한다 — 화면이 짐작해 칸을 짚으면 계정 존재를 흘린다(`D17` 「모르는 칸」)
    await screen.findByRole("alert");
    expect(container.querySelectorAll("[aria-invalid]")).toHaveLength(0);
  });
});
