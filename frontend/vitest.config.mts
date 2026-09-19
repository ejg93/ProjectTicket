import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

/**
 * 화면 테스트(`D8`).
 *
 * **고정하려는 것은 판단이지 그림이 아니다.** 화면이 서버 응답을 받아 무엇을 그릴지 고르는 자리 —
 * 오류 이름에 따른 문구, 상태에 따라 보이는 버튼 — 이 여기 걸린다. 여백과 색은 안 본다.
 *
 * **브라우저가 아니다.** jsdom 이라 프록시·세션 쿠키·CSRF 는 못 밟는다. 그쪽은 e2e(`46b`) 몫이다.
 */
export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./vitest.setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
  resolve: {
    // `@/` 별칭은 tsconfig 에 있는데 Vitest 는 그것을 안 읽는다. 여기서 같은 값을 준다.
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
});
