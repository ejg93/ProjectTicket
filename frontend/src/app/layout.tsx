import type { Metadata } from "next";
import Link from "next/link";

import "./globals.css";

export const metadata: Metadata = {
  title: "ProjectTicket",
  description: "공연 예매",
};

/**
 * 모든 화면이 두르는 껍데기.
 *
 * **`lang="ko"` 가 접근성 요건이다**(KWCAG 2.2 — 화면 낭독기가 어느 언어로 읽을지 정한다).
 *
 * 머리글은 **로그인 여부를 안 본다.** 여기서 세션을 읽으면 모든 화면이 매 요청 백엔드를 한 번씩 더
 * 두드리고, 그 판정이 화면의 판정과 두 벌이 된다(`D17` 「권한 없는 것은 숨긴다」). 지금은 지도만 건다.
 *
 * **맨 앞은 본문으로 건너뛰기다**(KWCAG 2.2 「반복 영역 건너뛰기」, `39-1`) — `<main>` 이 하나라 가리킬 곳도 하나다.
 * **발은 약관·처리방침이다** — 전자상거래법의 고지라 모든 화면에서 닿아야 한다.
 */
export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko">
      <body>
        <a href="#main" className="skip-link">
          본문으로 건너뛰기
        </a>
        <header>
          <nav aria-label="주요">
            <Link href="/">ProjectTicket</Link> <Link href="/me">내 계정</Link>{" "}
            <Link href="/login">로그인</Link>
          </nav>
        </header>
        <main id="main">{children}</main>
        <footer>
          <nav aria-label="약관">
            <Link href="/terms">이용약관</Link> <Link href="/privacy">개인정보 수집·이용 안내</Link>
          </nav>
        </footer>
      </body>
    </html>
  );
}
