/**
 * 대기열 입장 토큰의 자리(`43`, `D12`). **브라우저 `sessionStorage` 에 회차별로 둔다** — `admission:<performanceId>`.
 *
 * 쿠키로 하면 서버가 회차별 토큰을 골라 읽어야 하고, URL 로 나르면 새로고침·공유에 토큰이 남는다.
 * 탭을 닫으면 사라지는 것이 맞다 — 토큰은 짧게 살고(서버 TTL) 그 탭의 선점에만 쓴다.
 *
 * **저장소가 막혀도 서지 않는다**(사생활 모드·쿼터). 못 두면 선점이 `admission-required` 로 다시 줄로 보낸다 — 흐름은 그대로다.
 */

const keyOf = (performanceId: string) => `admission:${performanceId}`;

export function readAdmission(performanceId: string): string | null {
  try {
    return window.sessionStorage.getItem(keyOf(performanceId));
  } catch {
    return null;
  }
}

export function saveAdmission(performanceId: string, token: string): void {
  try {
    window.sessionStorage.setItem(keyOf(performanceId), token);
  } catch {
    // 못 두면 선점이 다시 줄로 보낸다
  }
}

export function dropAdmission(performanceId: string): void {
  try {
    window.sessionStorage.removeItem(keyOf(performanceId));
  } catch {
    // 지울 것이 없다
  }
}
