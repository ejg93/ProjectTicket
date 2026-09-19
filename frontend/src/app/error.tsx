"use client";

import Link from "next/link";

/**
 * 서버 오류(5xx)와 화면이 못 잡은 예외. **페이지 전체로 그린다**(`D17` 「오류는 자리를 가려서 보여준다」).
 *
 * **클라이언트 컴포넌트여야 한다** — Next 가 오류 경계로 쓰고, 다시 그려 보는 버튼이 여기서 온다.
 *
 * **원인을 안 보인다.** `error.message` 에는 서버 문구나 스택이 들어올 수 있고, 사용자는 그것으로
 * 할 수 있는 것이 없다(`D9`·`D10`). 찾을 때 쓰는 것은 `digest` 다 — 서버 로그의 같은 값과 이어진다.
 */
export default function ErrorScreen({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <div>
      <h1>화면을 불러오지 못했습니다</h1>
      <p>잠시 후 다시 시도해 주세요.</p>
      <p>
        <button type="button" onClick={reset}>
          다시 시도
        </button>
      </p>
      {error.digest ? <p className="muted">오류 번호: {error.digest}</p> : null}
      <p className="muted">
        <Link href="/">처음으로</Link>
      </p>
    </div>
  );
}
