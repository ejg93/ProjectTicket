import { cookies } from "next/headers";
import { redirect } from "next/navigation";

import { BACKEND_ORIGIN, toApiError } from "./api";

/**
 * 로그인해야 보는 것을 서버 컴포넌트에서 읽는다(`D16` 「인증이 필요한 데이터」).
 *
 * **파일이 갈린 이유가 `next/headers` 다.** 그것을 `api.ts` 에 들이면 그 파일을 가져다 쓰는
 * 클라이언트 컴포넌트가 전부 빌드에서 깨진다. 오류 변환은 저쪽 것을 그대로 쓴다.
 *
 * ## 왜 손으로 싣나
 *
 * 서버 컴포넌트를 그리는 것은 브라우저가 아니라 Next 서버다. 브라우저가 자동으로 붙이던 쿠키가
 * 여기서는 안 붙어서 **백엔드가 보기에 낯선 손님**이 된다.
 *
 * ```
 * 브라우저 ──(쿠키 자동)──> Next 서버 ──(안 붙음)──> 백엔드 → 401
 * ```
 *
 * **운반이 이 파일 하나에 갇혀 있어야 한다.** 만지는 자리가 여럿이면 세션 값을 로그에 찍거나,
 * 백엔드 아닌 곳에 싣거나, 응답이 캐시되는 사고가 각각 가능해진다. eslint 의 `no-restricted-imports` 가
 * 다른 파일의 `next/headers` 를 막는다.
 *
 * **프론트가 세션을 읽는 것이 아니다.** 쿠키에 든 것은 뜻 없는 식별자고 누구인지 판정하는 것은 백엔드뿐이다.
 */

/**
 * 백엔드로 넘길 쿠키. **목록으로 가둔다** — 요청에 온 쿠키를 통째로 넘기면 나중에 프론트가
 * 자기 쿠키를 하나 만들었을 때 그것까지 백엔드로 새어 나간다.
 */
const SESSION_COOKIE = "TICKETSESSION";

/**
 * 로그인 화면으로 보낼 때 붙이는 표시. 그 화면이 이유를 말한다.
 *
 * **둘로 가른다.** 로그인한 적 없는 사람에게 「만료되었습니다」라고 하면 사실이 아닌 것을 말하는
 * 것이고, 사용자는 자기가 뭘 잘못했다고 생각한다.
 */
const SESSION_EXPIRED = "/login?reason=session-expired";
const LOGIN_REQUIRED = "/login?reason=login-required";

/**
 * 세션을 실어서 부른다.
 *
 * **401 을 여기서 잡는다**(`D16` 「오류를 어느 층이 잡나」). 화면마다 잡으면 한 화면이 빠뜨렸을 때
 * **그 화면만 조용히 빈 목록**이 된다.
 *
 * @param path `/api` 로 시작하는 경로
 * @throws ApiError 401 말고 2xx 가 아닌 것. 403·404 는 부르는 화면이 잡는다
 */
export async function apiSession<T>(path: string): Promise<T> {
  const session = (await cookies()).get(SESSION_COOKIE);

  const response = await fetch(`${BACKEND_ORIGIN}${path}`, {
    headers: session ? { Cookie: `${SESSION_COOKIE}=${session.value}` } : {},
    // 사람마다 다른 응답이다. 캐시하면 남의 것이 보인다(`D16` 「캐시」).
    cache: "no-store",
  });

  if (response.status === 401) {
    redirect(session ? SESSION_EXPIRED : LOGIN_REQUIRED);
  }

  if (!response.ok) {
    throw await toApiError(response);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}
