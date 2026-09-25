import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { blocksOf, ConsentBody } from "./consent-body";

/**
 * 약관 본문이 **강조를 살려** 그려지는가(`39-1`, 약관규제법 제3조). 시드(`V3`)가 쓰는 표기 셋 — 머리·굵게·문단 — 과
 * 그 밖의 표기는 글자 그대로 남는 것.
 */
const BODY = `## 제2조 (예매의 성립)

**예매는 좌석을 선점한 뒤 결제가 승인된 시점에 성립합니다.**
선점만 한 상태에서 정해진 시간이 지나면 좌석은 자동으로 해제됩니다.

## 제3조 (취소와 환불)

# 한 단계 머리는 안 쓰는 표기라 글자 그대로입니다. <b>태그</b>도 글자입니다.`;

describe("약관 본문", () => {
  it("머리와 문단으로 가른다 — 빈 줄이 문단 끝이고 이어진 줄은 한 문단이다", () => {
    expect(blocksOf(BODY).map((b) => b.kind)).toEqual(["heading", "paragraph", "heading", "paragraph"]);
    expect(blocksOf(BODY)[1].text).toContain("성립합니다.** 선점만");
  });

  it("`**…**` 는 굵게, 나머지 표기는 글자 그대로 그린다", () => {
    const { container } = render(<ConsentBody body={BODY} />);

    expect(screen.getByRole("heading", { level: 2, name: "제2조 (예매의 성립)" })).toBeInTheDocument();
    expect(container.querySelector("strong")?.textContent).toBe("예매는 좌석을 선점한 뒤 결제가 승인된 시점에 성립합니다.");
    // 안 쓰는 표기와 HTML 은 해석하지 않는다 — React 가 글자로 이스케이프한다.
    expect(container.querySelector("b")).toBeNull();
    expect(screen.getByText(/# 한 단계 머리는/)).toHaveTextContent("<b>태그</b>");
  });

  it("약관 화면의 모양에 접근성 위반이 없다", async () => {
    // 페이지는 서버 컴포넌트라 여기서 못 부른다 — 같은 마크업(제목 + 시행일 + 본문)을 그린다.
    const { container } = render(
      <article>
        <h1>이용약관</h1>
        <p className="muted">시행일 2026. 9. 14.</p>
        <ConsentBody body={BODY} />
      </article>,
    );
    await expectNoAxeViolations(container);
  });
});
