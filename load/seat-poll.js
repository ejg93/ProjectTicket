// 좌석 현황 폴링의 스냅샷 비율(35d). 공개 GET 을 계단(100·1,000·5,000 rps)으로 치면서 `If-None-Match` 로 판을 묻는다.
//
// `D13` 의 목표는 「스냅샷 생성 ≤ 판 변경 횟수」다 — 폴링 수에 비례해 늘면 캐시가 안 먹는 것이다(`D20`).
// 판은 같은 시간에 `seat-rush.js`(`ROTATE=1`)가 선점으로 바꾼다. 판 변경 지표는 없어서 `seat:ver:{id}` 앞뒤 차로 센다.
// 스냅샷 생성은 Prometheus `seat_snapshot_built_total` 이다. 돌리는 법은 `README.md` 「좌석 폴링」.
//
// 세션이 필요 없다 — 좌석 현황은 공개 GET 이고 관문도 없다.
import http from "k6/http";
import { Counter, Trend } from "k6/metrics";

const BASE = __ENV.BASE_URL || "http://nginx:80";
const PERFORMANCE_ID = __ENV.PERFORMANCE_ID || "1";
const DURATION = __ENV.DURATION || "60s";
const MAX_VUS = Number(__ENV.MAX_VUS || 2000);

const poll200 = new Counter("poll_200");
const poll304 = new Counter("poll_304");
const pollLatency = new Trend("poll_latency_ms", true);

function step(name, startTime, rate, preAllocatedVUs) {
  return {
    executor: "constant-arrival-rate",
    rate,
    timeUnit: "1s",
    duration: DURATION,
    startTime,
    preAllocatedVUs,
    maxVUs: MAX_VUS,
    tags: { step: name },
  };
}

// 계단 사이 10초는 앞 계단의 꼬리가 다음 계단에 안 섞이게 둔다. Prometheus 를 계단마다 가를 때 그 틈을 경계로 쓴다.
const STEPS = [
  ["100", "0s", 100, 50],
  ["1000", "70s", 1000, 300],
  ["5000", "140s", 5000, 1000],
];

export const options = {
  scenarios: Object.fromEntries(STEPS.map(([name, start, rate, pre]) => [`r${name}`, step(name, start, rate, pre)])),
  thresholds: Object.assign(
    { http_req_failed: ["rate==0"] },
    ...STEPS.map(([name]) => ({
      [`poll_latency_ms{step:${name}}`]: ["p(95)>=0"],
      [`poll_200{step:${name}}`]: ["count>=0"],
      [`poll_304{step:${name}}`]: ["count>=0"],
    })),
  ),
};

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));

// VU 지역 — 화면이 하는 것과 같다. 받은 판을 들고 다음에 「이 판이면 본문 필요 없다」고 묻는다.
let etag = null;

export default function () {
  const headers = etag ? { "If-None-Match": etag } : {};
  const started = Date.now();
  const res = http.get(`${BASE}/api/performances/${PERFORMANCE_ID}/seats`, { headers });
  pollLatency.add(Date.now() - started);
  if (res.status === 304) poll304.add(1);
  else if (res.status === 200) poll200.add(1);
  etag = res.headers.Etag || res.headers.ETag || etag;
}
