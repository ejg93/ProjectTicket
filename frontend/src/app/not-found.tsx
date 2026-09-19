import Link from "next/link";

/**
 * 없는 주소. **페이지 전체로 그린다**(`D17`).
 *
 * `notFound()` 를 부른 화면이 여기로 온다 — 지금은 공연 상세가 `event-not-found` 를 받았을 때다.
 * **HTTP 도 404 다** — 화면 안에서 부르면 200 이 나가지만(`stack.md`), 이 파일은 라우팅이 부르는 자리라
 * 상태 코드가 같이 간다.
 */
export default function NotFound() {
  return (
    <div>
      <h1>찾을 수 없는 화면입니다</h1>
      <p>주소가 바뀌었거나 판매가 끝난 공연일 수 있습니다.</p>
      <p>
        <Link href="/events">공연 목록</Link>
      </p>
    </div>
  );
}
