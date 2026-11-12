#!/usr/bin/env bash
# Railway 서비스에 앱 변수를 한 번에 건다(`48`). 값은 Railway 의 참조 문법으로 애드온에서 읽는다 — 비밀이 저장소에 안 남는다.
# 애드온 이름(`Postgres`·`Redis`)이 참조의 앞머리다 — 다르면 `railway status` 로 맞춘다.
#
#   bash scripts/railway-env.sh <앱 서비스 이름>
#
# Kafka 애드온이 없다 — 소비자를 끄고(`SPRING_KAFKA_LISTENER_AUTO_STARTUP=false`) 아웃박스 발행 실패는 로그로 받아들인다(`stack.md`).
# 토픽 생성도 끈다(`SPRING_KAFKA_ADMIN_AUTO_CREATE=false`) — 켜 두면 AdminClient 가 없는 브로커로 재부트스트랩 로그를 쉬지 않고 찍는다(2026-11-12 첫 배포).
set -euo pipefail
service=${1:?앱 서비스 이름을 준다}

railway variable set -s "$service" \
  'DB_HOST=${{Postgres.PGHOST}}' \
  'POSTGRES_PORT=${{Postgres.PGPORT}}' \
  'POSTGRES_DB=${{Postgres.PGDATABASE}}' \
  'POSTGRES_USER=${{Postgres.PGUSER}}' \
  'POSTGRES_PASSWORD=${{Postgres.PGPASSWORD}}' \
  'REDIS_HOST=${{Redis.REDISHOST}}' \
  'REDIS_PORT=${{Redis.REDISPORT}}' \
  'SPRING_DATA_REDIS_PASSWORD=${{Redis.REDISPASSWORD}}' \
  'SESSION_COOKIE_SECURE=true' \
  'SPRING_KAFKA_LISTENER_AUTO_STARTUP=false' \
  'SPRING_KAFKA_ADMIN_AUTO_CREATE=false'
