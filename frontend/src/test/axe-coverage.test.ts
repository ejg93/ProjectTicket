import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";

import { describe, expect, it } from "vitest";

/**
 * 화면을 그리는 시험이 전부 axe 를 한 번은 돌리는지 본다(`screen-rules.md` 「접근성 — 기계가 보는 것 둘」, 점검 8차 `I8`).
 *
 * `jsx-a11y` 는 JSX 에 적힌 것만 읽고, 그려진 DOM(조건부 마크업·상태에 따른 `aria-*`·이어진 이름)은 `src/test/axe.ts` 가 본다.
 * 그런데 **그것은 시험이 불러야 돈다** — 점검 8차 때 `render(` 를 하는 시험 13 중 5(로그인·가입·선점·결제·공연 목록)가 안 불렀다.
 * 가장 사람이 많이 지나는 화면이 그물 밖이었다.
 *
 * **파일 단위다.** 한 시험 파일에서 한 번이라도 돌리면 통과다 — 모든 `render` 뒤에 돌리라는 것이 아니라, 그 컴포넌트가 한 번도 안 재어지는 것을 막는다.
 */

const SRC = join(process.cwd(), "src");

/** `render(` — Testing Library 의 그리기. 이것이 있는 파일이 화면 시험이다 */
const RENDERS = /\brender\(/;

/** 공용 도우미를 가져다 쓰는가. `axe-core` 를 직접 부르는 꼴은 없다 — 보고 형식이 그 도우미에 있다 */
const USES_AXE = /from "@\/test\/axe"/;

/** 첫 실측(`I8`): 화면 시험 13. 바닥은 그 절반 남짓 */
const MIN_SCREEN_TESTS = 8;

function testFilesUnder(dir: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      found.push(...testFilesUnder(path));
    } else if (entry.endsWith(".test.tsx")) {
      found.push(path);
    }
  }
  return found;
}

describe("화면을 그리는 시험은 axe 를 돌린다", () => {
  const screenTests = testFilesUnder(SRC).filter((path) => RENDERS.test(readFileSync(path, "utf8")));

  it("훑을 화면 시험이 있다", () => {
    expect(screenTests.length).toBeGreaterThanOrEqual(MIN_SCREEN_TESTS);
  });

  it("`render(` 를 하는 시험 파일은 `@/test/axe` 를 가져다 쓴다", () => {
    const offenders = screenTests.filter((path) => !USES_AXE.test(readFileSync(path, "utf8"))).map((path) => relative(SRC, path));
    expect(offenders, "그려진 DOM 을 한 번은 axe 로 본다 — expectNoAxeViolations(container)").toEqual([]);
  });
});
