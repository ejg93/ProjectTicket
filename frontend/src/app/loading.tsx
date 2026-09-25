/**
 * 첫 진입과 화면 사이의 뼈대(`39-1`). 없으면 서버가 그리는 동안 브라우저가 이전 화면에 머물러 누른 것이 안 먹은 것으로 보인다.
 *
 * `role="status"` 라 낭독기가 한 번 읽는다(`aria-live` 가 따라온다).
 */
export default function Loading() {
  return (
    <p role="status" className="muted">
      불러오는 중입니다…
    </p>
  );
}
