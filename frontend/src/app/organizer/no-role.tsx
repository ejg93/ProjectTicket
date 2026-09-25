import { ApiError } from "@/lib/api";

/**
 * 기획사 경로의 403(`organizer-forbidden`)을 페이지 전체로 그린다(`D17` 「권한 없는 것은 숨긴다」, 마무리 14차).
 * `notFound()` 로 안 바꾼다 — 경로 자체가 공개라 숨길 것이 없다(`D5` 「403 이냐 404 냐」). 판정은 서버 경로 접두 한 군데다.
 */
export const isNoOrganizerRole = (thrown: unknown) => thrown instanceof ApiError && thrown.slug === "organizer-forbidden";

export function NoOrganizerRole() {
  return (
    <div>
      <h1>권한이 없습니다</h1>
      <p>기획사에 소속된 계정만 쓸 수 있는 화면입니다.</p>
    </div>
  );
}
