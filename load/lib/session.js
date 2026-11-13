// 세션을 측정 밖에서 미리 만든다(35b). `setup()` 이 부르고, VU 는 돌려받은 쿠키로 jar 를 만든다.
//
// **왜 미리 만드나** — 반복마다 로그인하면 bcrypt 가 측정에 섞인다. 재려는 것은 예매·대기열이지 로그인이 아니다.
// 부하 계정의 해시는 비용 4 다(`prepare.sql`) — 1만 번 로그인해도 몇 분이다.
//
// **세 번 친다.** GET 으로 CSRF 를 받고, 로그인하고, 다시 GET — 로그인이 `XSRF-TOKEN` 을 지워서(22) 셋째 GET 의 새 토큰이라야
// 다음 POST 가 CSRF 를 지난다. 로그인 응답의 `TICKETSESSION` 이 최종값이다(설계 실측, 2026-10-13).
//
// `seat-rush.js` 는 이 파일을 import 하지 않고 같은 함수를 안에 든다 — `scripts/k8s-rollout-test.sh` 가 그 파일 하나를 configmap 으로 싣는다.
import http from "k6/http";
import { check } from "k6";
import { Rate } from "k6/metrics";

const SESSION = "TICKETSESSION";

// **503 을 본문으로 가른다**(`I17-16`). DB 상한(풀 대기 3초·잠금 3초·문장 10초)이 내는 `service-busy` 는 1% 미만을 받고
// (2026-11-13 사용자), 같은 503 이라도 `queue-unavailable`(Redis 장애) 같은 그 밖의 것은 0 이다. 응답 콜백은 503 을 통과시켜
// `http_req_failed` 에서 빼고, 여기 둘이 센다. `seat-rush.js` 는 같은 것을 안에 든다 — 고치면 둘 다.
export const serviceBusy = new Rate("service_busy");
export const unexpected503 = new Rate("unexpected_503");
export const BUSY_THRESHOLDS = { service_busy: ["rate<0.01"], unexpected_503: ["rate==0"] };

/** 응답 하나를 두 비율에 더하고 그대로 돌려준다 */
export function seen(res) {
  const unavailable = res.status === 503;
  const busy = unavailable && typeof res.body === "string" && res.body.includes("service-busy");
  serviceBusy.add(busy);
  unexpected503.add(unavailable && !busy);
  return res;
}

/** `http` 의 요청 셋을 [seen] 으로 감싼 것 — 대본은 요청을 이것으로 친다. jar·콜백은 `http` 그대로다 */
export const counted = {
  get: (...args) => seen(http.get(...args)),
  post: (...args) => seen(http.post(...args)),
  del: (...args) => seen(http.del(...args)),
};
const XSRF = "XSRF-TOKEN";

/** setup 의 요청은 `phase:prelogin` 꼬리표를 단다 — 측정 요청(`phase:run`)과 요약에서 가르려고 */
const PRELOGIN = { phase: "prelogin" };

function cookie(jar, base, name) {
  const found = jar.cookiesForURL(`${base}/`)[name];
  return found ? found[0] : null;
}

/**
 * 계정 `load{from}`~`load{to}` 를 차례로 로그인해 `{ index, session, xsrf }` 배열을 돌려준다.
 * 실패한 계정은 `session` 이 null 이다 — 부르는 쪽이 세어서 버린다.
 */
export function loginAll(base, from, to, password) {
  const out = [];
  const started = Date.now();
  for (let i = from; i <= to; i++) {
    const jar = new http.CookieJar();
    counted.get(`${base}/api/health`, { jar, tags: PRELOGIN });
    const res = counted.post(
      `${base}/api/auth/login`,
      JSON.stringify({ email: `load${i}@test.local`, password }),
      { jar, tags: PRELOGIN, headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": cookie(jar, base, XSRF) } },
    );
    check(res, { "미리 로그인 200": (r) => r.status === 200 }, PRELOGIN);
    counted.get(`${base}/api/health`, { jar, tags: PRELOGIN });
    out.push({ index: i, session: res.status === 200 ? cookie(jar, base, SESSION) : null, xsrf: cookie(jar, base, XSRF) });
    if ((i - from + 1) % 1000 === 0) console.log(`미리 로그인 ${i - from + 1}/${to - from + 1} — ${Math.round((Date.now() - started) / 1000)}초`);
  }
  console.log(`미리 로그인 끝 — ${out.length}개, ${Math.round((Date.now() - started) / 1000)}초`);
  return out;
}

/** 미리 만든 세션 하나로 jar 를 만든다 */
export function jarFor(base, s) {
  const jar = new http.CookieJar();
  jar.set(`${base}/`, SESSION, s.session);
  jar.set(`${base}/`, XSRF, s.xsrf);
  return jar;
}
