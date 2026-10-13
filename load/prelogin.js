// 세션 1만을 미리 만들어 파일로 남긴다(35c). `queue-surge.js` 가 그 파일을 `SharedArray` 로 읽는다.
//
// **왜 따로 한 판인가.** k6 는 `setup()` 의 반환값을 VU 마다 따로 복사한다 — 세션 1만 × VU 500 이 수 GB 가 되어
// Docker VM(7.75GB)이 메모리 부족으로 굳었다(35c 첫 판, 2026-10-13). `SharedArray` 는 한 벌만 두지만 init 단계의 파일에서만 만든다.
// 그래서 로그인을 이 판의 `setup()` 에서 하고(측정 밖이라는 결정은 그대로), `handleSummary` 가 `setup_data` 를 파일로 쓴다.
//
// 세션은 무활동 30분에 끝난다(`application.yml`) — 이 판 뒤 바로 `queue-surge.js` 를 돌린다.
import { loginAll } from "./lib/session.js";

const BASE = __ENV.BASE_URL || "http://nginx:80";
const ACCOUNTS = Number(__ENV.ACCOUNTS || 10000);
const PASSWORD = __ENV.PASSWORD || "demo-password-1234";
const OUT = __ENV.OUT || "/load/results/sessions.json";

export const options = { vus: 1, iterations: 1, setupTimeout: "20m" };

export function setup() {
  return { sessions: loginAll(BASE, 1, ACCOUNTS, PASSWORD) };
}

export default function () {}

export function handleSummary(data) {
  const sessions = data.setup_data.sessions;
  const failed = sessions.filter((s) => !s.session).length;
  return {
    [OUT]: JSON.stringify(sessions),
    stdout: `세션 ${sessions.length}개를 ${OUT} 에 썼다 — 실패 ${failed}\n`,
  };
}
