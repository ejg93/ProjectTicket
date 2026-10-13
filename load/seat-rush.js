// 오픈 순간을 흉내 낸다(31). 한 회차에 사람이 몰려 **같은 좌석 1천 석**을 두고 다툰다.
//
// 흐름은 실제 화면과 같다: 로그인 → 대기열 진입 → 폴링(2초) → 토큰을 받으면 선점.
// 관문(23)이 선점 앞에 서 있으므로 줄을 안 서면 429 다 — 그래서 이 스크립트가 줄부터 선다.
//
// 돌리는 법과 읽는 법은 `load/README.md`.
//
// `PRELOGIN=1` 이면 `setup()` 이 계정 `ACCOUNTS` 개를 미리 로그인하고 반복은 로그인을 건너뛴다 — 순수 예매 경로(35b).
// 미리 로그인 도우미는 `load/lib/session.js` 와 같은 것을 **이 파일 안에 든다** — `scripts/k8s-rollout-test.sh` 가
// 이 파일 하나만 configmap 으로 싣기 때문이다. 기본(`PRELOGIN` 없음)은 반복마다 로그인한다.
import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

const BASE = __ENV.BASE_URL || "http://nginx:80";
const PERFORMANCE_ID = __ENV.PERFORMANCE_ID || "1";
const ACCOUNTS = Number(__ENV.ACCOUNTS || 300);
const PASSWORD = __ENV.PASSWORD || "demo-password-1234";
const PRELOGIN = __ENV.PRELOGIN === "1";

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
  setupTimeout: "15m",
  // 측정 요청은 `phase:run`, 미리 로그인은 `phase:prelogin` — 아래 문턱이 요약에 `run` 만 따로 낸다.
  tags: { phase: "run" },
  // **실패는 5xx 와 연결 오류뿐이다**(`38`). 409(남이 먼저 잡음)·429(관문)는 정상 흐름이라 아래 콜백이 4xx 를 성공으로 센다 —
  // 기본값이면 4xx 까지 실패로 세서 「오류율 0」을 못 쓴다. 31·35 의 표는 옛 정의(0.5 문턱)로 이미 적혔다.
  thresholds: {
    http_req_failed: ["rate==0"],
    "http_req_duration{phase:run}": ["p(95)>=0"],
  },
};

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));

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

/** `load/lib/session.js` 와 같다 — 위 머리말 */
function preloginAll(count) {
  const tags = { phase: "prelogin" };
  const out = [];
  const started = Date.now();
  for (let i = 1; i <= count; i++) {
    const jar = new http.CookieJar();
    http.get(`${BASE}/api/health`, { jar, tags });
    const xsrf = jar.cookiesForURL(`${BASE}/`)["XSRF-TOKEN"];
    const res = http.post(
      `${BASE}/api/auth/login`,
      JSON.stringify({ email: `load${i}@test.local`, password: PASSWORD }),
      { jar, tags, headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": xsrf ? xsrf[0] : "" } },
    );
    check(res, { "미리 로그인 200": (r) => r.status === 200 }, tags);
    http.get(`${BASE}/api/health`, { jar, tags });
    const cookies = jar.cookiesForURL(`${BASE}/`);
    out.push({ session: cookies.TICKETSESSION ? cookies.TICKETSESSION[0] : null, xsrf: cookies["XSRF-TOKEN"] ? cookies["XSRF-TOKEN"][0] : null });
  }
  console.log(`미리 로그인 끝 — ${count}개, ${Math.round((Date.now() - started) / 1000)}초`);
  return out;
}

export function setup() {
  return PRELOGIN ? { sessions: preloginAll(ACCOUNTS) } : { sessions: null };
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

export default function (data) {
  const index = ((__VU - 1) % ACCOUNTS) + 1;
  let jar;
  let csrf;
  if (data.sessions) {
    const s = data.sessions[index - 1];
    jar = new http.CookieJar();
    jar.set(`${BASE}/`, "TICKETSESSION", s.session);
    jar.set(`${BASE}/`, "XSRF-TOKEN", s.xsrf);
    csrf = s.xsrf;
  } else {
    jar = http.cookieJar();
    csrf = logIn(jar, index);
  }
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
