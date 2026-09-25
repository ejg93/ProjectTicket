import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

import { describe, expect, it } from "vitest";

/**
 * `<form action={fn}>` 폼이 제출 중인지를 **손으로 들지 않는지** 본다(`frontend-rules.md` 「폼이 제출 중인지를 손으로 들지 않는다」, `39-2`).
 *
 * React 19 의 폼 `action` 은 `fn` 을 전환 안에서 돌리고 **`fn` 이 끝날 때까지 그 안의 상태 갱신을 커밋하지 않는다.**
 * 그래서 액션 첫 줄의 `setPending(true)` 가 화면에 못 닿고 `disabled={pending}` 이 **아무것도 안 막는다** —
 * 비행 중에 세 번 제출하면 세 번 다 나간다(ProjectShop `Q20-1` 이 실측으로 잡았다. 이 파일은 거기서 옮겼다).
 *
 * **린트로는 못 막는다.** ESLint 선택자는 노드 단위라 「이 파일에 `<form action=` 이 있고 **동시에** pending `useState` 가 있다」를
 * 표현할 수 없다 — 커스텀 플러그인을 새로 만들어야 하고, 그 값이 이 규칙 하나보다 크다.
 *
 * **`onSubmit` 폼은 반대다.** 거기서는 `useState` 로 드는 것이 맞다. 그래서 `action={` 을 가진 파일만 본다.
 */

/** vitest 는 `frontend/` 에서 돈다. `import.meta.url` 은 Windows 에서 앞에 슬래시가 붙어 안 맞는다 */
const SRC = join(process.cwd(), "src");

/** `<form ... action={` — 사이에 `ref=` 같은 것이 끼어도 잡는다 */
const FORM_ACTION = /<form[^>]*\saction=\{/;

/** `<form ... onSubmit={` — 반대쪽 꼴이다 */
const FORM_ONSUBMIT = /<form[^>]*\sonSubmit=\{/;

/** 공용 제출 버튼을 쓰는가 */
const SUBMIT_BUTTON = /\bSubmitButton\b/;

/**
 * `const [pending, setPending] = useState(` — 이름이 제출 중을 뜻하는 것만.
 * `React.useState(`·`useState<boolean>(` 도 같은 꼴이다(마무리 13차 독립 리뷰가 빈틈을 짚었다).
 */
const PENDING_STATE =
  /const\s*\[\s*(pending|sending|submitting|saving|posting)\s*,[^\]]*\]\s*=\s*(?:React\.)?useState\s*(?:<[^>]*>)?\s*\(/i;

/** 2026-09-26 실측: 화면 `.tsx` 스물, `action=` 폼 넷(로그인·가입·선점·결제). 바닥은 그 절반과 그 수다 */
const MIN_SCREENS = 10;
const MIN_ACTION_FORMS = 4;

function tsxFilesUnder(dir: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      found.push(...tsxFilesUnder(path));
    } else if (entry.endsWith(".tsx") && !entry.endsWith(".test.tsx")) {
      found.push(path);
    }
  }
  return found;
}

describe("폼이 제출 중인지를 손으로 들지 않는다", () => {
  const files = tsxFilesUnder(SRC);

  it("훑을 화면이 있다", () => {
    // 경로가 틀리면 0개를 읽고 조용히 통과한다. 그쪽이 규칙이 깨진 것보다 나쁘다.
    expect(files.length).toBeGreaterThan(MIN_SCREENS);
  });

  it("`<form action={}>` 을 쓰는 파일이 실제로 있다", () => {
    // 이것이 줄면 아래 규칙이 걸 파일을 잃는다 — 폼을 다른 꼴로 바꿨으면 이 바닥도 같이 고친다.
    const forms = files.filter((f) => FORM_ACTION.test(readFileSync(f, "utf8")));
    expect(forms.length).toBeGreaterThanOrEqual(MIN_ACTION_FORMS);
  });

  /**
   * **반대 방향이다.** `useFormStatus` 는 `action` 이 건 제출만 알아서, `onSubmit` 폼에 `SubmitButton` 을 끼우면
   * **언제나 `false`** 를 받아 **버튼이 한 번도 안 잠긴다.**
   */
  it("`<form onSubmit={}>` 파일이 `SubmitButton` 을 쓰지 않는다", () => {
    const offenders = files.filter((path) => {
      const source = readFileSync(path, "utf8");
      // **`action=` 이 같이 있으면 넘어간다** — 규칙은 폼 단위인데 검사는 파일 단위라,
      // 한 파일에 두 꼴이 섞이면 정당한 쪽을 오탐한다. 그때는 사람이 본다.
      return FORM_ONSUBMIT.test(source) && !FORM_ACTION.test(source) && SUBMIT_BUTTON.test(source);
    });

    expect(
      offenders.map((p) => p.slice(SRC.length)),
      "`useFormStatus` 는 `action` 이 건 제출만 안다 — `onSubmit` 폼에서는 언제나 false 라 버튼이 한 번도 안 잠긴다. 거기서는 `useState` 로 든다(`D16`)",
    ).toEqual([]);
  });

  it("그 파일들이 pending 을 `useState` 로 들지 않는다", () => {
    const offenders = files.filter((path) => {
      const source = readFileSync(path, "utf8");
      return FORM_ACTION.test(source) && PENDING_STATE.test(source);
    });

    expect(
      offenders.map((p) => p.slice(SRC.length)),
      "`<form action={fn}>` 은 fn 이 끝날 때까지 그 안의 상태 갱신을 커밋하지 않는다 — `disabled={pending}` 이 비행 중에 아무것도 안 막는다. " +
        "`components/submit-button.tsx` 를 쓴다(`D16`)",
    ).toEqual([]);
  });
});
