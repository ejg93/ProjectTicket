#!/usr/bin/env bash
# 훅 공통 조각. stdin 의 훅 JSON 을 한 번 읽어 두고 칸 하나를 꺼내는 함수를 준다(`B0-2`).
# 쓰는 쪽: `. "$(dirname "$0")/_tool-input.sh"` 뒤에 `tool_field command` / `tool_field file_path` / `hook_field stop_hook_active`.
# node 로 파싱하는 이유: JSON 안의 따옴표·역슬래시를 셸 정규식으로 풀면 틀린다.
HOOK_INPUT=$(cat)

# tool_input 안의 칸. 없으면 빈 문자열.
tool_field() {
  printf '%s' "$HOOK_INPUT" | node -e '
    let d = ""; process.stdin.on("data", x => d += x).on("end", () => {
      try { process.stdout.write(String((JSON.parse(d).tool_input || {})[process.argv[1]] || "")) } catch (e) {}
    })' "$1" 2>/dev/null
}

# 최상위 칸. 없으면 빈 문자열.
hook_field() {
  printf '%s' "$HOOK_INPUT" | node -e '
    let d = ""; process.stdin.on("data", x => d += x).on("end", () => {
      try { process.stdout.write(String(JSON.parse(d)[process.argv[1]] || "")) } catch (e) {}
    })' "$1" 2>/dev/null
}
