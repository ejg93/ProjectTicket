import { ApiError, type FieldError } from "./api";

/** 서버 칸 이름(`grades[1].code`)을 화면 칸 이름(`grade_code_1`)으로. 그 밖은 그대로다 — 서버가 이미 snake_case 로 준다(`D5`) */
export function inputNameOf(field: string): string {
  const row = /^(\w+?)s\[(\d+)\]\.(\w+)$/.exec(field);
  return row ? `${row[1]}_${row[3]}_${row[2]}` : field;
}

/**
 * 서버 `errors[]` 를 「화면 칸 이름 → 문구」로 바꾼다(`D17` 「오류는 자리를 가려서 보여준다」, `I8-2`).
 *
 * **서버 `message` 는 안 쓴다** — 칸 이름만 본다(`D17` 「원인을 화면에 안 적는다」). `text` 의 열쇠는 **지금 그려진 칸 이름**이다 —
 * 없는 칸을 짚으면 결과에 안 넣는다. 넣으면 그릴 자리가 없어 오류가 조용히 사라진다. 지목 못 한 것은 폼 전체 문구가 맡는다.
 */
export function fieldErrors(errors: readonly FieldError[], text: Record<string, string>): Record<string, string> {
  const named: Record<string, string> = {};
  for (const { field } of errors) {
    const name = inputNameOf(field);
    // 제 열쇠만 본다 — `text["constructor"]` 는 함수다(`login/page.tsx` 가 `Map` 으로 바꾼 것과 같은 이유)
    if (Object.hasOwn(text, name)) named[name] = text[name];
  }
  return named;
}

/**
 * 폼의 `catch` 가 쓴다. `validation-failed` 면 칸에 내릴 문구와 「폼 전체 문구도 띄우나(`rest`)」를 준다 —
 * 지목 못 한 칸이 하나라도 있거나 칸이 아예 없으면 `rest` 다. 다른 오류는 칸이 없으니 늘 `rest` 다.
 * 「모르는 칸」(로그인 실패처럼 어느 칸이 틀렸는지 서버가 안 말하는 것)은 칸을 지목하지 않는다(`D17`).
 */
export function fieldErrorsOf(thrown: unknown, text: Record<string, string>): { named: Record<string, string>; rest: boolean } {
  if (!(thrown instanceof ApiError) || thrown.slug !== "validation-failed") return { named: {}, rest: true };
  const named = fieldErrors(thrown.errors, text);
  const rest = thrown.errors.length === 0 || thrown.errors.some(({ field }) => !Object.hasOwn(named, inputNameOf(field)));
  return { named, rest };
}
