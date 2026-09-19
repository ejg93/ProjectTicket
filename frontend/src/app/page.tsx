import Link from "next/link";

/**
 * 첫 화면. 지도만 준다 — 목록은 `/events` 가 그린다(`40b`).
 *
 * **여기서 공연을 안 읽는다.** 같은 목록을 두 화면이 읽으면 줄 수·정렬이 갈리고, 한쪽만 고쳐진다.
 */
export default function HomePage() {
  return (
    <div>
      <h1>ProjectTicket</h1>
      <p>
        <Link href="/events">공연 보러 가기</Link>
      </p>
      <p className="muted">
        <Link href="/signup">회원가입</Link> · <Link href="/login">로그인</Link>
      </p>
    </div>
  );
}
