import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { PolicyView } from "./policy-view";

/**
 * 처리방침 화면(`39-3b`)이 예시라고 밝히고 절을 머리로 그리는가. 본문은 `V21` 시드의 꼴(`## ` 절 · 문단)이다.
 */
const POLICY = {
  code: "privacy_policy",
  title: "개인정보처리방침",
  version: 1,
  effective_at: "2026-01-01T00:00:00Z",
  body: `이 문서는 포트폴리오용 예시입니다.

## 1. 개인정보의 처리 목적

회사는 회원 식별과 예매 처리를 위해 개인정보를 처리합니다.

## 9. 개인정보 보호책임자

개인정보 보호책임자는 홍길동이며, 연락처는 privacy@example.com 입니다.`,
};

describe("개인정보처리방침", () => {
  it("예시라고 밝히고 절을 머리로 그리며 접근성 위반이 없다", async () => {
    const { container } = render(<PolicyView policy={POLICY} />);

    expect(screen.getByRole("heading", { level: 1, name: "개인정보처리방침" })).toBeInTheDocument();
    expect(screen.getByRole("note")).toHaveTextContent("포트폴리오 예시 — 실제 서비스가 아닙니다.");
    expect(screen.getAllByRole("heading", { level: 2 }).map((h) => h.textContent)).toEqual([
      "1. 개인정보의 처리 목적",
      "9. 개인정보 보호책임자",
    ]);
    expect(screen.getByText("시행일 2026-01-01")).toBeInTheDocument();
    await expectNoAxeViolations(container);
  });
});
