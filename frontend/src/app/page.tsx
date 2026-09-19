import Link from "next/link";

/**
 * 첫 화면. **자리표시다** — 공연 목록은 `40` 이 그린다.
 *
 * 빈 화면을 안 내놓는 이유는 `D17` 「빈 상태」다. 아무것도 없는 화면은 고장과 구분이 안 된다.
 */
export default function HomePage() {
  return (
    <div>
      <h1>ProjectTicket</h1>
      <p className="muted">공연 목록은 준비 중입니다. 먼저 계정을 만들어 주세요.</p>
      <p>
        <Link href="/signup">회원가입</Link> · <Link href="/login">로그인</Link>
      </p>
    </div>
  );
}
