import axe from "axe-core";

/**
 * 그려진 DOM 에 접근성 위반이 없는지 본다.
 *
 * **`jsx-a11y` 가 못 보는 자리를 본다.** 그쪽은 정적이라 JSX 에 적힌 것만 읽는다 — 조건부로 생긴 DOM,
 * 상태에 따라 바뀌는 `aria-*`, 실제로 이어진 이름은 그리고 나서야 안다.
 *
 * **`vitest-axe` 를 안 쓴다**(`stack.md`). 정식 판이 없고 vitest 최신과 안 붙는다. 여기서 쓰는 것은
 * axe 본체 하나고 그 위의 열 줄은 우리 것이다.
 *
 * **색 대비는 여기서 안 돈다** — jsdom 에 진짜 CSS 가 없다. 그래서 이것은 접근성을 보증하는 물건이 아니라
 * **되돌아가는 것을 막는 물건**이다. 대비는 `D17` 이 사람이 볼 규칙으로 든다.
 */
export async function expectNoAxeViolations(container: HTMLElement): Promise<void> {
  const result = await axe.run(container);

  if (result.violations.length === 0) {
    return;
  }

  // 규칙 이름만 내면 어디를 고쳐야 하는지 모른다. 어긴 마디와 고치는 법을 같이 적는다.
  const report = result.violations
    .map((violation) => {
      const where = violation.nodes.map((node) => `      ${node.html}`).join("\n");
      return `  ${violation.id} (${violation.impact}) — ${violation.help}\n${where}`;
    })
    .join("\n");

  throw new Error(`접근성 위반 ${result.violations.length}건\n${report}`);
}
