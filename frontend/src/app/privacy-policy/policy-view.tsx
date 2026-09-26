import { ConsentBody } from "@/components/consent-body";
import { date } from "@/lib/format";

/** `GET /api/policies/{code}`(`39-3a`). 서버 JSON 이 snake_case 라 **받은 그대로 적는다**(`D5`) */
export type PolicyDocument = {
  code: string;
  title: string;
  version: number;
  effective_at: string;
  body: string;
};

/**
 * 방침 문서 한 판(`39-3b`). 페이지는 서버 컴포넌트라 시험이 못 부른다 — 마크업을 여기 두고 페이지와 시험이 같이 쓴다.
 * **예시라는 것을 본문 앞에서 한 번 더 말한다** — 공개 저장소라 이름·연락처가 가상 값이다(사용자 결정, `39-3`).
 */
export function PolicyView({ policy }: { policy: PolicyDocument }) {
  return (
    <article>
      <h1>{policy.title}</h1>
      <p className="muted">시행일 {date(policy.effective_at)}</p>
      <p role="note">포트폴리오 예시 — 실제 서비스가 아닙니다.</p>
      <ConsentBody body={policy.body} />
    </article>
  );
}
