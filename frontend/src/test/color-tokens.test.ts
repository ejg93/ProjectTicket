import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";

import { describe, expect, it } from "vitest";

/**
 * 색이 토큰에만 있고 순수 흰·검이 없는지 본다(`screen-rules.md` 「시각 디자인은 아직 안 정한다」, 점검 8차 `I8`).
 *
 * 그 절이 지키라고 한 것은 둘뿐이다 — 색을 컴포넌트에 박지 않고 토큰으로 쓴다, 순수 `#000000`·`#ffffff` 를 안 쓴다.
 * 둘 다 문서에만 있었고 `globals.css` 에 `#ffffff` 가 셋 있었다. **axe 는 못 본다**(jsdom 에 CSS 가 없다) — 그래서 글자로 잰다.
 *
 * 디자인 청크가 토큰 체계를 다시 쓰는 날 이 시험은 그대로 남는다 — 그때도 순수 흰·검은 안 쓰고 색은 토큰이다.
 */

const SRC = join(process.cwd(), "src");
const TOKENS = join(SRC, "app", "globals.css");

/** `#fff`·`#ffffff`·`#000`·`#000000` — 대소문자 무관, 뒤에 16진 글자가 더 오면 다른 색이다 */
const PURE_BLACK_OR_WHITE = /#(?:fff|ffffff|000|000000)(?![0-9a-f])/gi;

/** 색 리터럴 — `#` 뒤 16진 3·4·6·8자리. 컴포넌트에 있으면 토큰을 안 쓴 것이다 */
const HEX_COLOR = /#(?:[0-9a-f]{8}|[0-9a-f]{6}|[0-9a-f]{3,4})(?![0-9a-z])/gi;

/** 첫 실측(`I8`): 화면 `.tsx` 마흔 남짓. 경로가 틀리면 0개를 읽고 조용히 통과하므로 바닥을 둔다 */
const MIN_SCREENS = 20;

function screenFilesUnder(dir: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      found.push(...screenFilesUnder(path));
    } else if (entry.endsWith(".tsx") && !entry.endsWith(".test.tsx")) {
      found.push(path);
    }
  }
  return found;
}

describe("색은 토큰이고 순수 흰·검이 없다", () => {
  const screens = [...screenFilesUnder(join(SRC, "app")), ...screenFilesUnder(join(SRC, "components"))];

  it("훑을 화면이 있다", () => {
    expect(screens.length).toBeGreaterThan(MIN_SCREENS);
  });

  it("토큰 파일에 순수 흰·검이 없다", () => {
    const css = readFileSync(TOKENS, "utf8");
    const lines = css.split("\n").flatMap((line, i) => (PURE_BLACK_OR_WHITE.test(line) ? [`${i + 1}: ${line.trim()}`] : []));
    expect(lines, "순수 #000000·#ffffff 를 안 쓴다 — 가까운 값으로 토큰을 만든다").toEqual([]);
  });

  it("화면 파일에 색 리터럴이 없다", () => {
    const offenders = screens.flatMap((path) => {
      const source = readFileSync(path, "utf8");
      const hits = source.match(HEX_COLOR) ?? [];
      return hits.length === 0 ? [] : [`${relative(SRC, path)}: ${hits.join(", ")}`];
    });
    expect(offenders, "색은 globals.css 의 토큰으로만 쓴다").toEqual([]);
  });
});
