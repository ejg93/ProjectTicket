// 오픈 순간 1만(49). 줄에 선 사람 1만이 입장하면 **한 석을 선점한다** — `queue-surge.js`(35c)는 입장하면 바로 반납했고
// `seat-rush.js`(35b)는 줄 없이 선점만 쟀다. 줄과 좌석 경쟁이 한 판에 같이 서는 것은 이것이 처음이다.
//
// 모양은 `queue-surge.js` 와 같다 — VU 500 이 계정 20 씩 들고 2초마다 묻는다. 세션은 `prelogin.js` 가 먼저 만든 파일을
// `SharedArray` 로 읽는다(`stack.md` 「k6」 — `setup()` 반환값은 VU 마다 복사된다).
//
// 좌석은 `FIRST_SEAT_ID + (계정 색인 mod SEATS)` — 계정 1만이 좌석 `SEATS` 를 나눠 노린다. 이긴 쪽은 끝이다(토큰은 서버가 반납, `23`).
// **진 쪽은 `DELETE` 로 자리를 돌려준다** — 409 는 서버가 토큰을 안 뺏어서, 안 돌려주면 정원 2,000 이 100초에 차고 입장이 멈춘다.
// 돌리는 법은 `README.md` 「오픈 순간」.
import http from "k6/http";
import { check, sleep } from "k6";
import { SharedArray } from "k6/data";
import { Counter, Trend } from "k6/metrics";
import { jarFor } from "./lib/session.js";

const BASE = __ENV.BASE_URL || "http://nginx:80";
const PERFORMANCE_ID = __ENV.PERFORMANCE_ID || "1";
const FIRST_SEAT_ID = Number(__ENV.FIRST_SEAT_ID || 1);
const SEATS = Number(__ENV.SEATS || 1000);
const ACCOUNTS = Number(__ENV.ACCOUNTS || 10000);
const VUS = Number(__ENV.VUS || 500);
const SESSIONS = new SharedArray("sessions", () => JSON.parse(open(__ENV.SESSIONS || "/load/results/sessions.json")).slice(0, ACCOUNTS));
const POLL_MS = 2000; // ADR 0003 — 폴링이 하트비트를 겸한다
const R = 20; // 입장 속도(명/초) — 예산 창(마무리 21차)
const BATCH = 100; // 첫 배치는 이론값(순번 ÷ R)이 틱(5초)보다 작아 비율에서 뺀다

const entered = new Counter("entered");
const enterFailed = new Counter("enter_failed");
const left = new Counter("left");
const releaseFailed = new Counter("release_failed");
const dropped = new Counter("dropped");
const seatsWon = new Counter("seats_won");
const seatsTaken = new Counter("seats_taken");
const holdOther = new Counter("hold_other");
const gateRejected = new Counter("gate_rejected");
const pollRound = new Trend("poll_round_ms", true);
const queueRank = new Trend("queue_rank");
const pollLatency = new Trend("poll_latency_ms", true);
const admissionWait = new Trend("admission_wait_ms", true);
const waitOverExpected = new Trend("wait_over_expected");
const holdWon = new Trend("hold_won_ms", true);
const holdLost = new Trend("hold_lost_ms", true);

export const options = {
  scenarios: {
    rush: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: __ENV.RAMP || "60s", target: VUS },
        { duration: __ENV.HOLD || "660s", target: VUS },
        { duration: "10s", target: 0 },
      ],
      gracefulRampDown: "10s",
    },
  },
  tags: { phase: "run" },
  // 문턱은 `D13` 에 이미 있는 칸만이다. 이긴 선점(`hold_won_ms`)은 첫 측정이라 문턱이 없다 — 이 판이 칸을 세운다(49 ⑤).
  thresholds: {
    http_req_failed: ["rate==0"],
    "http_req_duration{phase:run}": ["p(95)>=0"],
    poll_latency_ms: ["p(95)<140"],
    wait_over_expected: ["p(95)<2"],
  },
};

// 실패는 5xx 와 연결 오류뿐이다(`38`) — 409·429 는 정상 흐름이다
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));

/** 멱등키는 UUIDv4 여야 한다(`IdempotencyKeys`) */
function uuid() {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

let mine = null;

/** 제 몫의 계정만 꺼낸다. 좌석은 전역 색인으로 고른다 — VU 수를 바꿔도 같은 계정은 같은 좌석을 노린다 */
function mine_() {
  const per = Math.ceil(SESSIONS.length / VUS);
  const out = [];
  for (let i = (__VU - 1) * per; i < Math.min(__VU * per, SESSIONS.length); i++) {
    const s = SESSIONS[i];
    if (s.session) out.push({ s, jar: jarFor(BASE, s), seatId: FIRST_SEAT_ID + (i % SEATS), enteredAt: 0, rank: 0, done: false });
  }
  return out;
}

function release(a) {
  const res = http.del(`${BASE}/api/queue/${PERFORMANCE_ID}`, null, { jar: a.jar, headers: { "X-XSRF-TOKEN": a.s.xsrf } });
  check(res, { "반납 204": (r) => r.status === 204 });
  if (res.status === 204) left.add(1);
  else releaseFailed.add(1);
}

/** 입장했다 — 한 석을 선점한다. 이기면 끝, 지면 자리를 돌려준다 */
function hold(a, token) {
  const waited = Date.now() - a.enteredAt;
  admissionWait.add(waited);
  if (a.rank > BATCH) waitOverExpected.add(waited / 1000 / (a.rank / R));
  a.done = true;

  const started = Date.now();
  const res = http.post(`${BASE}/api/performances/${PERFORMANCE_ID}/reservations`, JSON.stringify({ seat_ids: [a.seatId] }), {
    jar: a.jar,
    headers: {
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": a.s.xsrf,
      "X-Admission-Token": token,
      "Idempotency-Key": uuid(),
    },
  });
  const took = Date.now() - started;
  check(res, { "선점이 201 이거나 409": (r) => r.status === 201 || r.status === 409 });

  if (res.status === 201) {
    seatsWon.add(1);
    holdWon.add(took);
    return;
  }
  if (res.status === 429) gateRejected.add(1);
  else if (res.status === 409 && String(res.json("type") || "").endsWith("seat-taken")) {
    seatsTaken.add(1);
    holdLost.add(took);
  } else holdOther.add(1);
  // 201 이 아니면 토큰을 쥔 채다 — 셋 모두 돌려준다. `seat-taken` 만 돌려주면 다른 4xx 가 정원을 갉는다
  release(a);
}

function enter(a) {
  const res = http.post(`${BASE}/api/queue/${PERFORMANCE_ID}`, null, { jar: a.jar, headers: { "X-XSRF-TOKEN": a.s.xsrf } });
  check(res, { "대기열 진입 200": (r) => r.status === 200 });
  if (res.status !== 200) {
    enterFailed.add(1);
    a.done = true;
    return;
  }
  entered.add(1);
  a.enteredAt = Date.now();
  const body = res.json();
  if (body.state === "admitted") return hold(a, body.admission_token);
  a.rank = body.rank || 0;
  queueRank.add(a.rank);
}

function poll(a) {
  const started = Date.now();
  const res = http.get(`${BASE}/api/queue/${PERFORMANCE_ID}`, { jar: a.jar });
  pollLatency.add(Date.now() - started);
  if (res.status === 404) {
    dropped.add(1);
    a.done = true;
    return;
  }
  if (res.status !== 200) return;
  const body = res.json();
  if (body.state === "admitted") hold(a, body.admission_token);
}

export default function () {
  const round = Date.now();
  if (mine === null) {
    mine = mine_();
    mine.forEach(enter);
  } else {
    const waiting = mine.filter((a) => !a.done);
    waiting.forEach(poll);
    if (waiting.length > 0) pollRound.add(Date.now() - round);
  }
  const rest = POLL_MS - (Date.now() - round);
  sleep(rest > 0 ? rest / 1000 : 0);
}
