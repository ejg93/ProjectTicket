#!/usr/bin/env bash
# 훅 공통 조각. stdin 의 훅 JSON 을 한 번 읽어 두고 칸 하나를 꺼내는 함수를 준다(`B0-2`).
# 쓰는 쪽: `. "$(dirname "$0")/_tool-input.sh"` 뒤에 `tool_field command` / `tool_field file_path` / `hook_field stop_hook_active`.
# node 로 파싱하는 이유: JSON 안의 따옴표·역슬래시를 셸 정규식으로 풀면 틀린다.
HOOK_INPUT=$(cat)

# **입력을 못 읽으면 여기서 막는다**(`G21`). 예전에는 칸마다 node 를 부르고 실패를 빈 문자열로 삼켜서,
# node 가 없거나 입력 모양이 바뀌면 훅 전부가 「해당 없음」으로 exit 0 이었다. `$(tool_field …)` 안의 exit 는
# 서브셸만 끝내므로 검사는 소싱하는 이 자리(훅 본 셸)에 둔다. node 는 한 번만 — 훅마다 칸을 하나씩 읽어서 횟수는 같다.
command -v node >/dev/null || { echo "node 가 없다 — 훅이 입력을 못 읽는다(scripts/hooks/_tool-input.sh)" >&2; exit 2; }
# 칸 셋을 U+001E 로 잇는다 — 명령 안의 줄바꿈이 남고, 마지막 칸만 `$( )` 가 끝 줄바꿈을 깎는다(true/false 라 상관없다).
_hook_fields=$(printf '%s' "$HOOK_INPUT" | node -e '
  let d = ""; process.stdin.on("data", x => d += x).on("end", () => {
    const j = JSON.parse(d), t = j.tool_input || {}, s = v => String(v ?? "");
    process.stdout.write([s(t.command), s(t.file_path), s(j.stop_hook_active)].join("\x1e"));
  })' 2>/dev/null) || { echo "훅 입력이 JSON 이 아니다 — 훅이 칸을 못 읽는다(scripts/hooks/_tool-input.sh)" >&2; exit 2; }
_hf_command=${_hook_fields%%$'\x1e'*}; _hook_fields=${_hook_fields#*$'\x1e'}
_hf_file_path=${_hook_fields%%$'\x1e'*}; _hf_stop_hook_active=${_hook_fields#*$'\x1e'}

# tool_input 안의 칸. 없으면 빈 문자열.
tool_field() {
  case $1 in
    command) printf '%s' "$_hf_command" ;;
    file_path) printf '%s' "$_hf_file_path" ;;
    *) echo "tool_field: 모르는 칸 「$1」 — _tool-input.sh 의 node 가 뽑는 칸에 더한다" >&2; return 1 ;;
  esac
}

# 최상위 칸. 없으면 빈 문자열.
hook_field() {
  case $1 in
    stop_hook_active) printf '%s' "$_hf_stop_hook_active" ;;
    *) echo "hook_field: 모르는 칸 「$1」 — _tool-input.sh 의 node 가 뽑는 칸에 더한다" >&2; return 1 ;;
  esac
}
