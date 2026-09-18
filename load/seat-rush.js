// 오픈 순간을 흉내 낸다(31). 한 회차에 사람이 몰려 **같은 좌석 1천 석**을 두고 다툰다.
//
// 흐름은 실제 화면과 같다: 로그인 → 대기열 진입 → 폴링(2초) → 토큰을 받으면 선점.
// 관문(23)이 선점 앞에 서 있으므로 줄을 안 서면 429 다 — 그래서 이 스크립트가 줄부터 선다.
//
// 돌리는 법과 읽는 법은 `load/README.md`.
import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

const BASE = __ENV.BASE_URL || "http://nginx:80";
const PERFORMANCE_ID = __ENV.PERFORMANCE_ID || "1";
const ACCOUNTS = Number(__ENV.ACCOUNTS || 300);
const PASSWORD = __ENV.PASSWORD || "demo-password-1234";

// 무엇이 몇 번 일어났나. k6 기본 지표(응답 시간·오류율) 위에 **업무 결과**를 더한다.
const seatsWon = new Counter("seats_won");
const seatsTaken = new Counter("seats_taken");
const gateRejected = new Counter("gate_rejected");
const admissionWait = new Trend("admission_wait_ms", true);
const holdLatency = new Trend("hold_latency_ms", true);

export const options = {
  scenarios: {
    rush: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: __ENV.RAMP || "20s", target: Number(__ENV.VUS || 100) },
        { duration: __ENV.HOLD || "40s", target: Number(__ENV.VUS || 100) },
        { duration: "10s", target: 0 },
      ],
      gracefulRampDown: "10s",
    },
  },
  // 여기서 막히면 그 자체가 결과다 — 숫자를 남기는 것이 목적이라 실패로 끝내지 않는다.
  thresholds: {
    http_req_failed: ["rate<0.5"],
  },
};

/** 멱등키는 **UUIDv4 여야 한다**(13 의 `IdempotencyKeys`). 아무 문자열이나 보내면 400 이다 */
function uuid() {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

/** 쿠키에 들어온 CSRF 토큰. 아무 GET 이나 한 번 치면 내려온다 */
function csrfToken(jar) {
  http.get(`${BASE}/api/health`, { jar });
  const cookies = jar.cookiesForURL(`${BASE}/`)["XSRF-TOKEN"];
  return cookies ? cookies[0] : null;
}

/**
 * 로그인해서 세션 쿠키를 얻는다.
 *
 * **로그인은 CSRF 토큰을 버리기만 한다**(22 에서 확인). 새 토큰은 다음 GET 에서 오므로 여기서 한 번 더 받는다 —
 * 안 받으면 다음 POST 가 토큰 없이 403 이다.
 */
function logIn(jar, index) {
  const res = http.post(
    `${BASE}/api/auth/login`,
    JSON.stringify({ email: `load${index}@test.local`, password: PASSWORD }),
    { jar, headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrfToken(jar) } },
  );
  check(res, { "로그인 200": (r) => r.status === 200 });
  return csrfToken(jar);
}

/** 줄에 서고 토큰이 나올 때까지 2초마다 묻는다(ADR 0003 — 폴링이 하트비트를 겸한다) */
function waitForAdmission(jar, csrf) {
  const started = Date.now();
  const entered = http.post(`${BASE}/api/queue/${PERFORMANCE_ID}`, null, {
    jar,
    headers: { "X-XSRF-TOKEN": csrf },
  });
  check(entered, { "대기열 진입 200": (r) => r.status === 200 });

  let body = entered.json();
  let tries = 0;
  while (body && body.state === "waiting" && tries < 30) {
    sleep(2);
    const polled = http.get(`${BASE}/api/queue/${PERFORMANCE_ID}`, { jar });
    if (polled.status !== 200) break;
    body = polled.json();
    tries += 1;
  }

  if (body && body.state === "admitted") {
    admissionWait.add(Date.now() - started);
    return body.admission_token;
  }
  return null;
}

export default function () {
  const index = ((__VU - 1) % ACCOUNTS) + 1;
  const jar = http.cookieJar();
  const csrf = logIn(jar, index);
  const token = waitForAdmission(jar, csrf);
  if (!token) return;

  // 좌석은 VU 마다 다른 자리를 노린다 — 전원이 같은 한 석을 치면 재는 것이 경합이 아니라 한 행의 잠금이다.
  const seatId = Number(__ENV.FIRST_SEAT_ID || 1) + ((__VU * 7) % Number(__ENV.SEATS || 1000));
  const started = Date.now();
  const res = http.post(
    `${BASE}/api/performances/${PERFORMANCE_ID}/reservations`,
    JSON.stringify({ seat_ids: [seatId] }),
    {
      jar,
      headers: {
        "Content-Type": "application/json",
        "X-XSRF-TOKEN": csrf,
        "X-Admission-Token": token,
        "Idempotency-Key": uuid(),
      },
    },
  );
  holdLatency.add(Date.now() - started);

  if (res.status === 201) seatsWon.add(1);
  else if (res.status === 409) seatsTaken.add(1);
  else if (res.status === 429) gateRejected.add(1);

  check(res, { "선점이 201 이거나 409": (r) => r.status === 201 || r.status === 409 });
  sleep(1);
}
