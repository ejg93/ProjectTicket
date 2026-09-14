#!/usr/bin/env bash
# 프롬프트 역할 문서(CLAUDE.md·PLAN.md·PROGRESS.md·doc/reference/*·스킬)가 구조적으로 부서졌는지 훑는다.
# 「규칙을 읽는 것」과 「지켜졌는지 훑는 것」은 다른 일이다 — 후자를 기계가 하는 자리다.
#
# 잡는 것: 제목 손상, 문장 통째 중복, 기준 문서 제목의 날짜, 존댓말, 분할표 칸 누락, 이력 순서, 「현재 상태」 비대.
set -uo pipefail
cd "$(dirname "$0")/.."

skill_files=(.claude/skills/*/SKILL.md)

title_check_files=(CLAUDE.md backend/CLAUDE.md PLAN.md PROGRESS.md "${skill_files[@]}" doc/reference/*.md)
dup_check_files=(CLAUDE.md PLAN.md PROGRESS.md backend/CLAUDE.md frontend/CLAUDE.md frontend/AGENTS.md "${skill_files[@]}" doc/reference/*.md)
honorific_check_files=(CLAUDE.md PLAN.md PROGRESS.md backend/CLAUDE.md frontend/CLAUDE.md frontend/AGENTS.md "${skill_files[@]}" doc/reference/*.md)
dated_title_files=(doc/reference/*.md)

fail=0

for f in "${title_check_files[@]}"; do
  [ -f "$f" ] || continue
  first_line=$(head -n 1 "$f")
  # 스킬 파일은 frontmatter 가 먼저다 — 닫는 `---` 다음의 첫 줄을 본다.
  if [ "$first_line" = "---" ]; then
    first_line=$(awk 'NR>1 && /^---$/{f=1; next} f && NF{print; exit}' "$f")
  fi
  case "$first_line" in
    "#"*) ;;
    *) echo "[제목 손상] $f — 첫 줄이 '#' 로 안 시작함: ${first_line:0:60}..."; fail=1 ;;
  esac
done

for f in "${dup_check_files[@]}"; do
  [ -f "$f" ] || continue
  # 코드펜스 안을 지우고, 표 행·제목·20자 미만 줄을 뺀 뒤 완전 중복만 본다.
  dups=$(awk '/^```/{c=!c; next} !c' "$f" \
    | grep -vE '^[[:space:]]*(\||#+[[:space:]]|>)' \
    | sed '/^[[:space:]]*$/d' \
    | awk 'length($0) >= 20' \
    | sort | uniq -d)
  if [ -n "$dups" ]; then
    echo "[중복 문장] $f:"; echo "$dups" | sed 's/^/    /'; fail=1
  fi
done

for f in "${dated_title_files[@]}"; do
  [ -f "$f" ] || continue
  # `external-references.md` 는 날짜가 내용이다 — 언제 원문으로 확인했나.
  case "$f" in doc/reference/external-references.md) continue ;; esac
  dated=$(grep -nE '^#+[[:space:]].*[12][0-9]{3}-[0-9]{2}-[0-9]{2}' "$f")
  if [ -n "$dated" ]; then
    echo "[제목에 날짜] $f — 기준 문서는 「지금 무엇이 맞나」만 답한다. 이력은 PROGRESS.md 로:"
    echo "$dated" | sed 's/^/    /'; fail=1
  fi
done

for f in "${honorific_check_files[@]}"; do
  [ -f "$f" ] || continue
  # `screen-rules.md` 는 화면 문구를 정의하는 자리라 존댓말이 본문이다.
  case "$f" in doc/reference/screen-rules.md) continue ;; esac
  # 인용은 위반이 아니다 — 백틱·「」·큰따옴표 안을 걷어내고 본다.
  # 코드펜스는 빈 줄로 바꿔 줄 번호를 지킨다. perl -CSD 인 이유: sed 의 멀티바이트 문자 클래스가 조용히 안 먹는다.
  hits=$(awk '/^```/{c=!c; print ""; next} c{print ""; next} {print}' "$f" \
    | perl -CSD -pe 's/`[^`]*`//g; s/\x{300C}.*?\x{300D}//g; s/"[^"]*"//g' \
    | grep -nE '(습니다|합니다|하세요|입니다)')
  if [ -n "$hits" ]; then
    echo "[존댓말] $f — 개발자가 읽는 글은 평서형이다(CLAUDE.md 「글 작성 규칙」 4번):"
    echo "$hits" | sed 's/^/    /'; fail=1
  fi
done

# 분할표의 안 닫힌 행에 축·강제 지점·닫힘이 다 있나. 기준선은 내리기만 한다.
# 행 판정: 번호 칸·이름 칸의 취소선, 선행 칸의 `완료` 면 닫힌 행. `#` 은 표 머리.
plan_open_incomplete_baseline=0
plan_open_incomplete=$(awk '/^## 청크 분할표/{on=1} /^## 이 계획을 고칠 때/{on=0} on && /^\| [^-|*][^|]*\|/{
    n=split($0,c,"|"); id=c[2]; gsub(/^ +| +$/,"",id); nm=c[3]; gsub(/^ +/,"",nm);
    last=c[n-1]; gsub(/^ +| +$/,"",last);
    if (id=="#" || id ~ /^~~/ || nm ~ /^~~/ || last=="완료") next;
    if ($0 !~ /\*\*축\*\*/ || $0 !~ /\*\*강제 지점\*\*/ || $0 !~ /\*\*닫힘\*\*/) k++
  } END{print k+0}' PLAN.md)
if [ "$plan_open_incomplete" -gt "$plan_open_incomplete_baseline" ]; then
  echo "[분할표 칸 누락] PLAN.md — 안 닫힌 행 중 축·강제 지점·닫힘이 빠진 것이 ${plan_open_incomplete}개 (기준선 ${plan_open_incomplete_baseline}). 새 행에는 넷을 다 적는다(PLAN.md 「청크 분할표」)"
  fail=1
elif [ "$plan_open_incomplete" -lt "$plan_open_incomplete_baseline" ]; then
  echo "[기준선 내릴 것] PLAN.md — 칸 빠진 행이 ${plan_open_incomplete}개로 줄었다. plan_open_incomplete_baseline 을 그 수로 내린다"
fi

# 이력이 날짜순인가. 앞줄보다 이른 날짜가 오면 센다.
history_unsorted_baseline=0
history_unsorted=$(awk '/^## 이력/{on=1; next} on && /^## /{on=0} on && /^\| [0-9]{4}-[0-9]{2}-[0-9]{2} \|/{
    d=substr($0,3,10); if (prev != "" && d < prev) k++; prev=d
  } END{print k+0}' PROGRESS.md)
if [ "$history_unsorted" -gt "$history_unsorted_baseline" ]; then
  echo "[이력 순서] PROGRESS.md — 앞줄보다 이른 날짜가 ${history_unsorted}곳 (기준선 ${history_unsorted_baseline}). 새 줄은 표 맨 아래에 붙인다"
  fail=1
fi

# 「현재 상태」는 표다. 서사가 붙으면 세션마다 hook 이 통째로 주입한다.
state_lines=$(awk '/^## 현재 상태$/{on=1; next} /^## /{on=0} on' PROGRESS.md | wc -l)
if [ "$state_lines" -gt 25 ]; then
  echo "[현재 상태 비대] PROGRESS.md — 「현재 상태」가 ${state_lines}줄이다(상한 25). 표만 남기고 서사는 이력으로"
  fail=1
fi

[ "$fail" -eq 0 ] && echo "이상 없음 — 검사한 파일 전부 통과"
exit "$fail"
