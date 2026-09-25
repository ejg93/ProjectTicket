import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { describe, expect, it } from "vitest";

/**
 * `screen-rules.md` 「화면 지도」의 경로마다 화면 파일이 있는가(`39-1`, `D17` 「아직 없는 화면은 자리표시로 둔다」).
 *
 * 지도에 있는데 파일이 없으면 링크가 404 로 떨어진다 — 사용자는 고장으로 읽는다. 아직 안 만든 화면도
 * 자리표시(`components/coming-soon.tsx`)가 파일로 서 있어야 한다. **지도가 먼저다** — 화면을 만들기 전에 지도에 선다.
 *
 * 한쪽만 본다(`D8`) — 지도에 없는 파일(예: `app/loading.tsx`)은 여기서 안 센다.
 */

/** vitest 는 `frontend/` 에서 돈다 */
const APP = join(process.cwd(), "src", "app");
const SCREEN_RULES = join(process.cwd(), "..", "doc", "reference", "screen-rules.md");

/** 2026-09-26 실측으로 지도의 경로가 열넷이다. 이보다 적게 읽으면 표 꼴이 바뀐 것이다 */
const MIN_PATHS = 10;

/** 「화면 지도」 표의 첫 칸에서 백틱 경로를 모은다. 한 칸이 둘을 들기도 한다(`/login`·`/signup`). `/*` 는 그 접두의 첫 화면이다 */
function mappedPaths(): string[] {
  const lines = readFileSync(SCREEN_RULES, "utf8").split(/\r?\n/);
  const start = lines.findIndex((line) => line.startsWith("## 화면 지도"));
  const header = lines.findIndex((line, i) => i > start && line.startsWith("| 경로 |"));
  const paths: string[] = [];
  for (let i = header + 2; start >= 0 && header > 0 && i < lines.length && lines[i].startsWith("|"); i++) {
    const cell = lines[i].split("|")[1];
    for (const match of cell.matchAll(/`(\/[^`]*)`/g)) {
      paths.push(match[1].replace(/\/\*$/, ""));
    }
  }
  return paths;
}

function pageFileOf(path: string): string {
  return path === "/" ? join(APP, "page.tsx") : join(APP, ...path.slice(1).split("/"), "page.tsx");
}

describe("화면 지도의 경로마다 화면이 있다", () => {
  const paths = mappedPaths();

  it("지도를 읽었다", () => {
    // 표 꼴이 바뀌면 0개를 읽고 아래가 조용히 통과한다 — 「못 읽었다」와 「갈렸다」를 가른다(`D18`).
    expect(paths.length, "screen-rules.md 「화면 지도」 표에서 경로를 못 읽었다").toBeGreaterThanOrEqual(MIN_PATHS);
  });

  it("경로마다 `app/<경로>/page.tsx` 가 있다", () => {
    const missing = paths.filter((path) => !existsSync(pageFileOf(path)));
    expect(missing, "지도에 있는데 화면 파일이 없다 — 아직 안 만든 화면이면 자리표시(`ComingSoon`)를 놓는다(`D17`)").toEqual([]);
  });
});
