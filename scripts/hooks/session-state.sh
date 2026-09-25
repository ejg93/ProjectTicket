#!/usr/bin/env bash
# SessionStart. `PROGRESS.md` 「현재 상태」 절을 그대로 컨텍스트에 넣는다 — 그 블록이 단일 진실이다(CLAUDE.md 「재개 프로토콜」).
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
sed -n '/^## 현재 상태/,/^## 이력/p' PROGRESS.md | sed '$d'
exit 0
