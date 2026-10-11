import { expect, test, type Page } from "@playwright/test";

/**
 * 가입부터 결제까지 한 바퀴(`46b`). 데모 시드(`local` 프로필)의 첫 공연·열린 회차를 쓴다.
 *
 * 선택자는 역할과 이름이다(`D17`) — 좌석은 `aria-label`, 오류·상태는 `role=alert|status`. `data-testid` 를 안 쓴다:
 * 낭독기가 못 찾는 것을 시험이 찾으면 접근성 회귀를 못 본다.
 * 재사용 DB 에서 여러 번 돌므로 계정은 매번 새로, 좌석은 남은 것 중 아무거나 고른다.
 */
test("가입한 관객이 좌석 하나를 잡고 결제까지 마친다", async ({ page }) => {
  const email = `e2e-${Date.now()}@test.local`;
  const password = "e2e-password-1234";

  await page.goto("/signup");
  await page.getByLabel("이메일", { exact: true }).fill(email);
  await page.getByLabel("이름", { exact: true }).fill("종단 관객");
  await page.getByLabel("비밀번호", { exact: true }).fill(password);
  for (const required of await page.getByRole("checkbox", { name: /\(필수\)/ }).all()) {
    await required.check();
  }
  await page.getByRole("button", { name: "가입" }).click();
  await expect(page).toHaveURL(/\/login/);

  await page.getByLabel("이메일", { exact: true }).fill(email);
  await page.getByLabel("비밀번호", { exact: true }).fill(password);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page).toHaveURL(/\/me/);

  await page.goto("/events");
  await page.getByRole("link", { name: "겨울 콘서트" }).click();
  await page.getByRole("heading", { name: "회차" }).waitFor();
  await page.getByRole("listitem").getByRole("link").first().click();
  await expect(page.getByRole("heading", { name: "좌석 선택" })).toBeVisible();

  await holdOneSeat(page);

  // 열린 회차는 관문을 지난다(`QueueService`). 대기열이 입장을 주면 좌석 화면으로 돌려보낸다 — 다시 잡는다.
  if (/\/queue\//.test(page.url())) {
    await expect(page).toHaveURL(/\/performances\/\d+/, { timeout: 60_000 });
    await holdOneSeat(page);
  }

  await expect(page).toHaveURL(/\/reservations\/\d+/);
  await page.getByLabel("카드번호", { exact: true }).fill("4242424242424242");
  await page.getByRole("button", { name: /결제$/ }).click();
  await expect(page.getByRole("heading", { name: "티켓" })).toBeVisible();
});

async function holdOneSeat(page: Page) {
  const open = page.getByRole("checkbox", { name: /선택 가능/ });
  await open.first().waitFor();
  const count = await open.count();
  await open.nth(Math.floor(Math.random() * count)).check();
  await page.getByRole("button", { name: "선택한 좌석 예매" }).click();
  await expect(page).toHaveURL(/\/(reservations|queue)\/\d+/);
}
