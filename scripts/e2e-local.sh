#!/usr/bin/env bash
# e2e 를 로컬에서 CI `e2e` 잡과 같은 꼴로 돈다(`G19`) — 빈 DB · `bootJar` · `java -jar --spring.profiles.active=local`.
# `verify.sh --full` 이 백엔드가 안 떠 있으면 부른다. 손으로도 돌린다: `bash scripts/e2e-local.sh`.
#
# 마무리 24차에서 손으로 세 번 막혔던 것을 여기 박는다:
#   - 8080 은 다른 저장소 컨테이너가 쥘 수 있다 → 백엔드는 `E2E_PORT`(기본 8090)
#   - 재사용 DB(`ticket-db`)는 시드 회차가 지나 `/events` 가 빈다(`stack.md` 「건너뛰는 탓에…」) → 늘 빈 클러스터 `ticket-check-db`
#   - 화면의 `/api` 프록시 목적지는 `next build` 때 굳는다 → 그 포트로 빌드한다(`E2E_BUILT=1` 이면 부른 쪽이 이미 빌드했다)
# 끝나면(초록·빨강 무관) 앱 프로세스와 `ticket-check-db` 를 내린다. compose 의 redis·kafka 는 둔다 — 다른 레인이 쓴다.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PORT=${E2E_PORT:-8090}
DB_PORT=${E2E_DB_PORT:-5499}
DB=ticket-check-db
[ -f .env ] && { set -a; . ./.env; set +a; }

pid=
cleanup() {
  [ -n "$pid" ] && kill "$pid" 2>/dev/null
  docker rm -f "$DB" >/dev/null 2>&1
  return 0
}
trap cleanup EXIT

bash scripts/docker-up.sh || exit 1
docker compose up -d --wait redis kafka >/dev/null || { echo "compose redis·kafka 가 안 떴다 — docker compose ps 로 본다"; exit 1; }

docker rm -f "$DB" >/dev/null 2>&1
docker run -d --rm --name "$DB" -e POSTGRES_USER="${POSTGRES_USER:-ticket}" -e POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-ticket}" \
  -e POSTGRES_DB=ticket_check -p "$DB_PORT:5432" postgres:17-alpine >/dev/null \
  || { echo "$DB 를 못 띄웠다 — $DB_PORT 가 막혔으면 E2E_DB_PORT 로 바꾼다"; exit 1; }

(cd backend && ./gradlew bootJar -q) || exit 1
jar=$(find backend/build/libs -name '*.jar' ! -name '*-plain.jar' | head -1)
[ -n "$jar" ] || { echo "bootJar 산출물이 없다 — backend/build/libs"; exit 1; }

# DB 가 받기 전에 앱이 뜨면 Hikari 가 첫 연결에서 선다
for _ in $(seq 1 30); do docker exec "$DB" pg_isready -q -U "${POSTGRES_USER:-ticket}" 2>/dev/null && break; sleep 1; done

log=backend/build/e2e-backend.log
POSTGRES_DB=ticket_check POSTGRES_PORT=$DB_PORT SERVER_PORT=$PORT java -jar "$jar" --spring.profiles.active=local > "$log" 2>&1 &
pid=$!
curl -sf --retry 40 --retry-delay 3 --retry-all-errors "localhost:$PORT/api/health" >/dev/null \
  || { echo "백엔드가 $PORT 에 안 떴다 — $log 끝:"; tail -20 "$log"; exit 1; }
echo "== e2e 백엔드 $PORT · 빈 DB $DB_PORT"

origin="http://localhost:$PORT"
if [ "${E2E_BUILT:-}" != 1 ]; then
  (cd frontend && BACKEND_ORIGIN=$origin npm run build >/dev/null) || exit 1
fi
(cd frontend && BACKEND_ORIGIN=$origin npm run e2e)
