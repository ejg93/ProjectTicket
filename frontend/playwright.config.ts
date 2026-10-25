import { defineConfig, devices } from "@playwright/test";

/**
 * 예매 한 바퀴를 실제 브라우저로 돈다(`46b`, `D8`). 백엔드는 따로 떠 있어야 한다 — `local` 프로필(데모 시드).
 * Next 는 `next build` 뒤의 `next start` 로 띄운다. 개발 서버는 첫 요청마다 컴파일해서 시한이 흔들린다.
 */
export default defineConfig({
  testDir: "e2e",
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: "http://localhost:3000",
    trace: "on-first-retry",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "npm run start",
    url: "http://localhost:3000/login",
    // 3000 에 떠 있는 서버에 붙지 않는다 — 남의 서버로 시험을 돌면 초록이어도 우리 화면이 아니다(`I10-3`, 마무리 24차의 8080 과 같은 꼴).
    // 막혀 있으면 Playwright 가 「already used」로 선다. CI 와 같다.
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
