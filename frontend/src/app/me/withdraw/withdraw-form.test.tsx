import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { WithdrawForm } from "./withdraw-form";

/**
 * 탈퇴 폼(`44c`). 비밀번호를 **본문으로** 보내고, 틀리면 로그인 화면으로 튕기지 않고 그 자리에서 말한다.
 */

const replace = vi.fn();
const router = { push: vi.fn(), replace, refresh: vi.fn() };
vi.mock("next/navigation", () => ({
  useRouter: () => router,
}));

function respond(body: unknown, status: number) {
  const spy = vi.fn().mockResolvedValue(
    status === 204 ? new Response(null, { status }) : new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }),
  );
  vi.stubGlobal("fetch", spy);
  return spy;
}

async function submit(password: string) {
  await userEvent.type(screen.getByLabelText("비밀번호"), password);
  await userEvent.click(screen.getByRole("button", { name: "탈퇴하기" }));
}

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=t";
});

afterEach(() => {
  vi.unstubAllGlobals();
  replace.mockClear();
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

describe("탈퇴", () => {
  it("비밀번호를 본문에 실어 DELETE 를 보내고 성공하면 처음 화면으로 간다", async () => {
    const spy = respond(null, 204);
    render(<WithdrawForm />);

    await submit("my-password-123456");

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/"));
    const call = spy.mock.calls.find((c) => String(c[0]) === "/api/me");
    expect(call?.[1].method).toBe("DELETE");
    expect(JSON.parse(call?.[1].body)).toEqual({ password: "my-password-123456" });
  });

  it("비밀번호가 틀리면 그 자리에서 말하고 로그인으로 안 튕긴다", async () => {
    respond({ type: "tag:projectticket.example,2026:login-failed", detail: "틀렸다" }, 401);
    render(<WithdrawForm />);

    await submit("wrong-password-xx");

    expect(await screen.findByRole("alert")).toHaveTextContent("비밀번호가 맞지 않습니다");
    expect(replace).not.toHaveBeenCalled();
  });

  it("접근성 위반이 없다", async () => {
    const { container } = render(<WithdrawForm />);
    await expectNoAxeViolations(container);
  });
});
