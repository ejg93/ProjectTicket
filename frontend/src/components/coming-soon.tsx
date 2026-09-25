import Link from "next/link";

/**
 * 지도에 있는데 아직 안 만든 화면의 자리표시(`D17` 「아직 없는 화면은 자리표시로 둔다」, `39-1`).
 *
 * **링크는 먼저 건다.** 안 걸면 화면이 생길 때마다 넣었다 뺐다 하고, 걸고 404 면 사용자는 고장으로 읽는다.
 * 자리표시는 **그 화면을 만드는 청크가 지운다** — 남아 있으면 그 청크가 안 끝난 것이다.
 */
export function ComingSoon({ title }: { title: string }) {
  return (
    <div>
      <h1>{title}</h1>
      <p>준비 중인 화면입니다.</p>
      <p>
        <Link href="/events">공연 목록으로 가기</Link>
      </p>
    </div>
  );
}
