// 줄에 선 사람 1만(35c). 계정·세션 1만이 한 회차의 대기열에 서서 2초마다 순번을 묻고, 입장하면 바로 자리를 돌려준다.
//
// **VU 1만이 아니다**(사용자 결정 2026-10-13). VU 하나가 1~5MB 라 1만이면 k6 가 먼저 서고, nginx 가 워커 1 × 1,024 연결이라
// 진짜 동시 연결은 500 언저리가 천장이다. 그래서 VU 수백이 계정을 여럿(`ACCOUNTS / VUS`)씩 들고 차례로 묻는다.
// 「사람」은 계정이고 대기열 ZSET 의 원소도 계정이라, 줄 길이는 VU 수가 아니라 계정 수만큼 선다.
//
// **입장하면 바로 `DELETE`** — 반납이 없으면 정원 2,000 이 100초에 차고 토큰 TTL 10분 동안 입장이 0 이 된다(`D12`).
// 그러면 2,001번째부터 「입장 대기 ≤ 2 × 순번 ÷ R」(`D13`)을 댈 수가 없다.
//
// 세션은 `prelogin.js` 가 먼저 한 판으로 만들어 파일에 둔다 — 측정 구간에 로그인이 없다. 여기서는 그 파일을 `SharedArray` 로
// 읽는다. `setup()` 반환값은 VU 마다 복사돼 세션 1만 × VU 500 이 Docker VM 메모리를 넘겼다(첫 판). 돌리는 법은 `README.md` 「대기열 1만」.
import http from "k6/http";
import { check, sleep } from "k6";
import { SharedArray } from "k6/data";
import { Counter, Trend } from "k6/metrics";
import { jarFor } from "./lib/session.js";

const BASE = __ENV.BASE_URL || "http://nginx:80";
const PERFORMANCE_ID = __ENV.PERFORMANCE_ID || "1";
const ACCOUNTS = Number(__ENV.ACCOUNTS || 10000);
const VUS = Number(__ENV.VUS || 500);
const SESSIONS = new SharedArray("sessions", () => JSON.parse(open(__ENV.SESSIONS || "/load/results/sessions.json")).slice(0, ACCOUNTS));
const POLL_MS = 2000; // ADR 0003 — 폴링이 하트비트를 겸한다
const R = 20; // 입장 속도(명/초) — `AdmissionService` 5초마다 100
const BATCH = 100; // 첫 배치는 이론값(순번 ÷ R)이 틱(5초)보다 작아 비율에서 뺀다

const entered = new Counter("entered");
const enterFailed = new Counter("enter_failed");
const left = new Counter("left");
const releaseFailed = new Counter("release_failed");
const dropped = new Counter("dropped");
const pollRound = new Trend("poll_round_ms", true);
const queueRank = new Trend("queue_rank");
const pollLatency = new Trend("poll_latency_ms", true);
const admissionWait = new Trend("admission_wait_ms", true);
const waitOverExpected = new Trend("wait_over_expected");

export const options = {
  scenarios: {
    surge: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: __ENV.RAMP || "60s", target: VUS },
        { duration: __ENV.HOLD || "600s", target: VUS },
        { duration: "10s", target: 0 },
      ],
      gracefulRampDown: "10s",
    },
  },
  tags: { phase: "run" },
  thresholds: {
    http_req_failed: ["rate==0"],
    "http_req_duration{phase:run}": ["p(95)>=0"],
  },
};

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));

// VU 지역 상태. k6 는 VU 사이에 바뀌는 상태를 못 나눈다 — 계정을 VU 마다 나눠 들고 각자 관리한다.
let mine = null;

/** 제 몫의 계정만 꺼낸다 — `SharedArray` 는 원소를 꺼낼 때만 VU 메모리에 복사한다 */
function mine_() {
  const per = Math.ceil(SESSIONS.length / VUS);
  const out = [];
  for (let i = (__VU - 1) * per; i < Math.min(__VU * per, SESSIONS.length); i++) {
    const s = SESSIONS[i];
    if (s.session) out.push({ s, jar: jarFor(BASE, s), enteredAt: 0, rank: 0, done: false });
  }
  return out;
}

function finish(a, body) {
  const waited = Date.now() - a.enteredAt;
  admissionWait.add(waited);
  if (a.rank > BATCH) waitOverExpected.add(waited / 1000 / (a.rank / R));
  const res = http.del(`${BASE}/api/queue/${PERFORMANCE_ID}`, null, { jar: a.jar, headers: { "X-XSRF-TOKEN": a.s.xsrf } });
  check(res, { "반납 204": (r) => r.status === 204 });
  // 반납이 안 됐으면 자리를 쥔 채다 — 셈을 따로 둔다. `left + release_failed + dropped = entered` 가 VU 상태 관리가 안 샜다는 뜻이다
  if (res.status === 204) left.add(1);
  else releaseFailed.add(1);
  a.done = true;
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
  if (body.state === "admitted") return finish(a, body);
  a.rank = body.rank || 0;
  queueRank.add(a.rank);
}

function poll(a) {
  const started = Date.now();
  const res = http.get(`${BASE}/api/queue/${PERFORMANCE_ID}`, { jar: a.jar });
  pollLatency.add(Date.now() - started);
  if (res.status === 404) {
    // 하트비트가 90초 끊겨 줄에서 빠졌다 — 폴링 간격이 2초를 크게 넘었다는 뜻이다
    dropped.add(1);
    a.done = true;
    return;
  }
  if (res.status !== 200) return;
  const body = res.json();
  if (body.state === "admitted") finish(a, body);
}

export default function () {
  const round = Date.now();
  if (mine === null) {
    mine = mine_();
    mine.forEach(enter);
  } else {
    const waiting = mine.filter((a) => !a.done);
    waiting.forEach(poll);
    // 바퀴 하나의 길이 — 계정 하나가 다음에 묻기까지의 간격이다. 2초를 넘으면 하트비트(90초)까지의 여유가 그만큼 준다
    if (waiting.length > 0) pollRound.add(Date.now() - round);
  }
  // 한 바퀴가 2초보다 빠르면 남은 시간을 쉰다. 느리면 바로 다음 바퀴 — 그 간격 자체가 결과다(`poll_latency_ms`).
  const rest = POLL_MS - (Date.now() - round);
  sleep(rest > 0 ? rest / 1000 : 0);
}
