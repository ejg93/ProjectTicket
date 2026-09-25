#!/usr/bin/env bash
# 프롬프트 역할 문서(CLAUDE.md·PLAN.md·PROGRESS.md·doc/reference/*·스킬)가 구조적으로 부서졌는지 훑는다.
# 「규칙을 읽는 것」과 「지켜졌는지 훑는 것」은 다른 일이다 — 후자를 기계가 하는 자리다.
#
# 잡는 것: 제목 손상, 문장 통째 중복, 기준 문서 제목의 날짜, 존댓말, 분할표 칸 누락, 이력 순서, 「현재 상태」 비대.
#
# 범위 모드(`B0-2`): 인자로 파일을 주면 그 파일만 본다 — 편집 훅이 쓴다(전체는 12초, 한 파일은 1초 안).
# 인자 없으면 전체(CI·마무리). PLAN·PROGRESS 의 구조 검사는 그 파일이 범위에 들 때만 돈다.
set -uo pipefail
cd "$(dirname "$0")/.."

skill_files=(.claude/skills/*/SKILL.md)

title_check_files=(CLAUDE.md backend/CLAUDE.md PLAN.md PROGRESS.md "${skill_files[@]}" doc/reference/*.md)
dup_check_files=(CLAUDE.md PLAN.md PROGRESS.md backend/CLAUDE.md frontend/CLAUDE.md frontend/AGENTS.md "${skill_files[@]}" doc/reference/*.md)
honorific_check_files=(CLAUDE.md PLAN.md PROGRESS.md backend/CLAUDE.md frontend/CLAUDE.md frontend/AGENTS.md "${skill_files[@]}" doc/reference/*.md)
dated_title_files=(doc/reference/*.md)

# 범위: 인자를 저장소 상대 경로로 맞춰 두고, 목록마다 그 안에 든 것만 남긴다. 목록 밖 파일은 조용히 통과.
scope=()
root_posix=$(pwd); root_win=$(pwd -W 2>/dev/null || pwd)   # 훅은 `C:\...` 꼴을 준다. Git Bash 의 pwd 는 `/c/...`.
for a in "$@"; do
  a=${a//\\//}; a=${a#"$root_posix/"}; a=${a#"$root_win/"}; a=${a#./}; scope+=("$a")
done
in_scope() { [ "${#scope[@]}" -eq 0 ] && return 0; local x; for x in "${scope[@]}"; do [ "$x" = "$1" ] && return 0; done; return 1; }
narrow() { local out=() f; for f in "$@"; do in_scope "$f" && out+=("$f"); done; printf '%s\n' "${out[@]}"; }
mapfile -t title_check_files < <(narrow "${title_check_files[@]}")
mapfile -t dup_check_files < <(narrow "${dup_check_files[@]}")
mapfile -t honorific_check_files < <(narrow "${honorific_check_files[@]}")
mapfile -t dated_title_files < <(narrow "${dated_title_files[@]}")

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
  # 깨진 UTF-8 (`B0-3`). `awk -v` 가 `\…` 를 이스케이프로 먹은 행이 한 번 들어갔고, 아래 perl 검사가 죽으면서 「이상 없음」이 났다.
  if ! iconv -f UTF-8 -t UTF-8 "$f" >/dev/null 2>&1; then
    echo "[UTF-8 깨짐] $f — 유효하지 않은 바이트가 있다. 셸로 넣은 줄이면 awk -v·echo -e 의 이스케이프를 의심한다"; fail=1
  fi
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
  # 표 셀도 본다(`B0-3`) — 이 저장소는 내용의 대부분이 표라 행을 빼면 검사가 거의 눈을 감는다. 30자 이상 셀의 완전 중복.
  # `screen-rules.md` 는 화면 문구 정의라 같은 문구가 여러 행에 서는 것이 정상이다.
  case "$f" in doc/reference/screen-rules.md) continue ;; esac
  cell_dups=$(awk -F'|' '/^\|/{for(i=2;i<NF;i++){s=$i; gsub(/^ +| +$/,"",s); if(length(s)>=30) print s}}' "$f" | sort | uniq -d)
  if [ -n "$cell_dups" ]; then
    echo "[중복 셀] $f — 같은 말이 표의 두 칸에 있다. 한쪽을 지우거나 다르게 적는다:"; echo "$cell_dups" | sed 's/^/    /'; fail=1
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
  # 어미 열(`B0-3`): ~니다(「아니다」는 평서형이라 뺀다)·세요·십시오·해요·어요·예요·네요·나요·까요·죠. 넓힐 때 전체 문서에 0건이었다.
  # 마지막 단계가 perl 이라 「없음」이 0 으로 끝난다 — 그래서 파이프가 0 이 아니면 검사 자체가 죽은 것이다(`B0-3` ⑧).
  # `-Mutf8` 이 없으면 정규식의 한글 리터럴이 바이트로 남아 아무것도 안 잡힌다 — `-CSD` 는 입출력만 풀지 소스는 안 푼다.
  hits=$(awk '/^```/{c=!c; print ""; next} c{print ""; next} {print}' "$f" \
    | perl -CSD -pe 's/`[^`]*`//g; s/\x{300C}.*?\x{300D}//g; s/"[^"]*"//g' \
    | perl -CSD -Mutf8 -ne 'print "$.:$_" if /(?<!아)니다|세요|십시오|해요|어요|예요|네요|나요|까요|죠(?=[.,)!? ]|$)/') \
    || { echo "[검사 실패] $f — 존댓말 검사 파이프가 죽었다(perl). 위에 [UTF-8 깨짐] 이 있으면 그것이 원인이다"; fail=1; }
  if [ -n "$hits" ]; then
    echo "[존댓말] $f — 개발자가 읽는 글은 평서형이다(CLAUDE.md 「글 작성 규칙」 4번):"
    echo "$hits" | sed 's/^/    /'; fail=1
  fi
done

# 분할표의 안 닫힌 행에 축·강제 지점·닫힘이 다 있나. 기준선은 내리기만 한다.
# 행 판정: 번호 칸·이름 칸의 취소선, 선행 칸의 `완료` 면 닫힌 행. `#` 은 표 머리.
if in_scope PLAN.md; then
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

# 설계 행(`/design`)의 형식(`B0-3`). 「번호 절차」가 있는 안 닫힌 행은 결정·실패 사다리도 있고, 닫힘이 ①~⑦ 하나를 가리킨다.
# 하나라도 빠지면 실행 세션(Opus)이 그 행에서 멈춘다 — 설계가 없애려는 것이 바로 그 멈춤이다.
plan_design_incomplete=$(awk '/^## 청크 분할표/{on=1} /^## 이 계획을 고칠 때/{on=0} on && /^\| [^-|*][^|]*\|/{
    n=split($0,c,"|"); id=c[2]; gsub(/^ +| +$/,"",id); nm=c[3]; gsub(/^ +/,"",nm);
    last=c[n-1]; gsub(/^ +| +$/,"",last);
    if (id=="#" || id ~ /^~~/ || nm ~ /^~~/ || last=="완료") next;
    if ($0 !~ /\*\*번호 절차\*\*/) next;
    if ($0 !~ /\*\*결정\*\*/ || $0 !~ /\*\*실패 사다리\*\*/ || $0 !~ /\*\*닫힘\*\*:? *(①|②|③|④|⑤|⑥|⑦)/) k++
  } END{print k+0}' PLAN.md)
if [ "$plan_design_incomplete" -gt 0 ]; then
  echo "[설계 행 누락] PLAN.md — 번호 절차가 있는 행 ${plan_design_incomplete}개에 결정·실패 사다리·「닫힘: ①~⑦」 중 빠진 것이 있다(/design 「행 형식」)"
  fail=1
fi
fi

if in_scope PROGRESS.md; then
# 이력이 날짜순인가. 앞줄보다 이른 날짜가 오면 센다.
history_unsorted_baseline=0
history_unsorted=$(awk '/^## 이력/{on=1; next} on && /^## /{on=0} on && /^\| [0-9]{4}-[0-9]{2}-[0-9]{2} \|/{
    d=substr($0,3,10); if (prev != "" && d < prev) k++; prev=d
  } END{print k+0}' PROGRESS.md)
if [ "$history_unsorted" -gt "$history_unsorted_baseline" ]; then
  echo "[이력 순서] PROGRESS.md — 앞줄보다 이른 날짜가 ${history_unsorted}곳 (기준선 ${history_unsorted_baseline}). 새 줄은 표 맨 아래에 붙인다"
  fail=1
fi

# 완료 이력 행의 커밋 칸(`B0-3`). 마지막 행 하나는 면제 — 커밋 뒤에야 해시를 알아서 다음 커밋이 채운다.
history_no_hash=$(awk '/^## 이력/{on=1; next} on && /^## /{on=0} on && /^\| [0-9]{4}-[0-9]{2}-[0-9]{2} \|/{rows[++n]=$0}
  END{for(i=1;i<n;i++){m=split(rows[i],c,"|"); r=c[4]; gsub(/^ +/,"",r); h=c[m-1]; gsub(/ /,"",h); if(r ~ /^완료/ && h=="") k++} print k+0}' PROGRESS.md)
if [ "$history_no_hash" -gt 0 ]; then
  echo "[이력 해시 빈 칸] PROGRESS.md — 완료 행 ${history_no_hash}개의 커밋 칸이 비었다(마지막 행 제외). 커밋 해시를 채운다"
  fail=1
fi

# 「현재 상태」는 표다. 서사가 붙으면 세션마다 hook 이 통째로 주입한다.
state_lines=$(awk '/^## 현재 상태$/{on=1; next} /^## /{on=0} on' PROGRESS.md | wc -l)
if [ "$state_lines" -gt 25 ]; then
  echo "[현재 상태 비대] PROGRESS.md — 「현재 상태」가 ${state_lines}줄이다(상한 25). 표만 남기고 서사는 이력으로"
  fail=1
fi
fi

[ "$fail" -eq 0 ] && echo "이상 없음 — 검사한 파일 전부 통과"
exit "$fail"
