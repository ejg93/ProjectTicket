#!/usr/bin/env bash
# PreToolUse(Bash·PowerShell). `gradlew` 를 부르는 명령에 JAVA_HOME 지정이 없으면 막는다 —
# 이 환경의 기본이 JDK 11 이라 Gradle 이 안 뜬다(CLAUDE.md 「검증」). 한글이 든 줄(설명문)은 안 본다.
. "$(dirname "$0")/_tool-input.sh"
c=$(tool_field command)

if printf '%s\n' "$c" | perl -CS -ne 'print unless /\p{Hangul}/' | grep -qE '(^|[;&|])[[:space:]]*(\./)?gradlew([[:space:]]|$)' \
   && ! printf '%s' "$c" | grep -qE 'JAVA_HOME[[:space:]]*=|\$env:JAVA_HOME'; then
  echo 'backend 명령 앞에 JDK 를 지정한다(CLAUDE.md 「검증」). 이 환경의 기본은 JDK 11 이라 Gradle 이 안 뜬다. Bash 는 JAVA_HOME="C:/Program Files/Java/jdk-25" 를 앞에 붙이고, PowerShell 은 $env:JAVA_HOME = "C:/Program Files/Java/jdk-25"; 를 앞에 둔다.' >&2
  exit 2
fi
exit 0
