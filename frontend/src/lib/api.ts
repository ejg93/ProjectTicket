/**
 * 서버를 부르는 유일한 통로.
 *
 * 여기를 안 거치는 `fetch` 를 쓰지 않는다(`D16`, eslint `no-restricted-globals` 가 막는다).
 * CSRF 헤더와 오류 변환이 여기에만 있어서, 직접 부르면 어떤 응답만 변환을 안 거친 채 화면에 닿는다.
 *
 * 입구가 셋이다. **도는 곳과 누구의 것이냐로 갈린다.**
 *
 * ```
 * api()        클라이언트 컴포넌트 · 상대경로 · 쿠키를 브라우저가 붙인다 · CSRF 를 싣는다
 * apiPublic()  서버 컴포넌트       · 절대주소 · 쿠키 없음               · 읽기 전용
 * apiSession() 서버 컴포넌트       · 절대주소 · 쿠키를 손으로 싣는다     · 읽기 전용
 * ```
 *
 * {@link api} 는 쿠키를 `document.cookie` 로 읽어서 **브라우저에서만 돈다.**
 *
 * **{@link apiSession} 만 파일이 다르다**(`api-session.ts`). `next/headers` 를 쓰는데 그것을 여기 들이면
 * 이 파일을 가져다 쓰는 **클라이언트 컴포넌트가 전부 빌드에서 깨진다.** 세 입구가 같은 오류 처리를
 * 쓰도록 아래 둘({@link toApiError}·{@link BACKEND_ORIGIN})을 내보낸다.
 *
 * **표기를 안 바꾼다.** 서버 JSON 이 snake_case 고 화면은 받은 그대로 읽는다(`D5`) —
 * 변환 층을 두면 같은 필드에 이름이 둘 생긴다. 이식 원본(ProjectShop)은 카멜로 바꿨는데
 * 그쪽은 응답 규약이 달라서다.
 */

/**
 * 우리 오류 `type` 의 접두어. 서버 `ErrorCode.type` 과 같은 값이다.
 *
 * `tag:` URI 다(RFC 4151). **바뀌는 자리가 여기 하나다** — 화면들은 접두어를 모르고 슬러그만 본다.
 * 그러지 않으면 접두어를 바꿀 때 화면 전부를 같이 고쳐야 하고, 하나를 빠뜨리면 그 화면만
 * 조용히 기본 문구로 떨어진다.
 */
const ERROR_TYPE_PREFIX = "tag:projectticket.example,2026:";

export class ApiError extends Error {
  /**
   * 접두어를 뗀 오류 이름. **화면은 이것으로 분기한다**(`D5`).
   *
   * 우리가 낸 오류가 아니면 빈 문자열이다 — 프록시나 다른 서버가 낸 `problem+json` 이
   * 우연히 우리 이름과 겹치는 일이 없다.
   */
  readonly slug: string;

  constructor(
    readonly status: number,
    readonly type: string,
    readonly detail: string,
    readonly traceId?: string,
  ) {
    super(detail);
    this.name = "ApiError";
    this.slug = type.startsWith(ERROR_TYPE_PREFIX) ? type.slice(ERROR_TYPE_PREFIX.length) : "";
  }
}

/** CSRF 토큰이 담겨 오는 쿠키와 그것을 돌려보낼 헤더. 이름은 Spring Security 기본값이다(`SecurityConfig`) */
const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";

/** 토큰이 없을 때 한 번 두드려서 쿠키를 받아 오는 곳. 인증이 필요 없는 경로여야 한다 */
const CSRF_PRIMER = "/api/health";

/** 이 메서드들은 서버 상태를 안 바꾼다. CSRF 토큰이 필요 없다 */
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

type Json = unknown;

/**
 * 서버를 부르고 응답을 돌려준다. **브라우저에서만 돈다.**
 *
 * @param path `/api` 로 시작하는 경로. 포트를 적지 않는다 — 프록시가 같은 출처로 넘긴다
 * @param init.idempotencyKey 좌석이나 돈이 움직이는 POST 에 필수다(`D4`). 만드는 쪽은 화면이고,
 *                            **재시도에도 같은 값을 보내야 한다** — 새로 만들면 서버가 재전송이 아니라
 *                            새 요청으로 보고 하나 더 만든다
 * @throws ApiError 서버가 2xx 가 아닌 것을 줬을 때
 */
export async function api<T>(
  path: string,
  init: { method?: string; body?: Json; idempotencyKey?: string } = {},
): Promise<T> {
  const method = init.method ?? "GET";
  const headers: Record<string, string> = {};

  if (init.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }

  if (init.idempotencyKey !== undefined) {
    headers["Idempotency-Key"] = init.idempotencyKey;
  }

  if (!SAFE_METHODS.has(method)) {
    headers[CSRF_HEADER] = await csrfToken();
  }

  const response = await fetch(path, {
    method,
    headers,
    // 세션 쿠키를 싣는다. 같은 출처라 기본값도 같지만, 프록시를 걷어내는 날 이 줄이 없으면
    // 로그인만 조용히 안 된다.
    credentials: "same-origin",
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });

  // 세션이 끊겼다. 화면마다 문구를 만들지 않고 로그인으로 보낸다.
  //
  // 로그인·가입 경로는 뺀다. 거기서 나는 401 은 「세션이 없다」가 아니라 「이번 시도가 틀렸다」라
  // 보내 봐야 같은 화면이고, 대신 그 폼이 어느 칸도 지목하지 않는 오류로 그린다.
  //
  // 서버 컴포넌트 쪽은 `api-session.ts` 가 같은 일을 한다. 층이 둘이라 두 군데인 것이지 규칙이 둘인 것이 아니다.
  if (response.status === 401 && !path.startsWith("/api/auth/")) {
    // **「만료됐다」고 말하지 않는다.** 세션 쿠키가 HttpOnly 라 여기서는 **로그인한 적이 있었는지를 모른다** —
    // 한 번도 로그인 안 한 사람에게 만료를 말하면 사실이 아닌 것을 말하는 것이다(`D17`).
    // 둘을 가르는 것은 쿠키를 읽을 수 있는 `api-session.ts` 쪽이고, 이쪽은 둘 다 참인 문구를 쓴다.
    window.location.replace("/login?reason=login-required");
    // 이동이 시작돼도 이 함수는 계속 돈다. 여기서 안 끊으면 부르는 쪽이 오류 문구를 띄우고,
    // 사용자는 로그인 화면으로 넘어가기 직전에 그것을 본다.
    await new Promise(() => {});
  }

  if (!response.ok) {
    throw await toApiError(response);
  }

  // 204 는 본문이 없다. 파싱하면 그 자리에서 터진다.
  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

/**
 * 백엔드 주소. `next.config.ts` 의 rewrite 가 쓰는 것과 같은 값에서 온다.
 *
 * 서버 컴포넌트는 프록시를 안 지난다. 브라우저가 아니라 Next 서버가 부르는 것이라
 * 상대경로에 붙일 출처가 없어서 절대 주소가 필요하다.
 */
export const BACKEND_ORIGIN = process.env.BACKEND_ORIGIN ?? "http://localhost:8080";

/**
 * 로그인 없이 볼 수 있는 것을 서버 컴포넌트에서 읽는다.
 *
 * **쿠키를 안 싣는다.** 공개 데이터는 누구에게나 같으므로 실을 이유가 없고, 안 실으면
 * **사람마다 다른 응답이 섞일 수 없다** — 캐시를 켜는 날 그 위험이 안 생긴다.
 * **CSRF 도 없다.** 읽기만 하는 입구라 서버가 토큰을 안 본다.
 *
 * @throws ApiError 서버가 2xx 가 아닌 것을 줬을 때
 */
export async function apiPublic<T>(path: string): Promise<T> {
  // 명시한다. Next 판마다 캐시 기본값이 갈려서 기대지 않는다(`D16` 「캐시」).
  const response = await fetch(`${BACKEND_ORIGIN}${path}`, { cache: "no-store" });

  if (!response.ok) {
    throw await toApiError(response);
  }

  return (await response.json()) as T;
}

/**
 * 쿠키에 든 CSRF 토큰. 없으면 한 번 두드려서 받아 온다.
 *
 * 서버는 **토큰을 읽을 때** 쿠키를 심는다. 화면만 띄우고 바로 로그인을 누르면 아직 아무 요청도
 * 안 나가서 쿠키가 없고, 그 자리에서 403 이 된다. 그래서 여기서 한 번 채운다.
 *
 * 쿠키 값을 그대로 헤더에 싣는다 — `CookieCsrfTokenRepository.withHttpOnlyFalse()` 가
 * 평문으로 비교한다(`SecurityConfig`).
 */
async function csrfToken(): Promise<string> {
  const existing = readCookie(CSRF_COOKIE);
  if (existing) {
    return existing;
  }

  await fetch(CSRF_PRIMER, { credentials: "same-origin" });

  const issued = readCookie(CSRF_COOKIE);
  if (!issued) {
    // 여기까지 오면 서버 설정이 바뀐 것이다. 403 을 받고 원인을 찾는 것보다 먼저 말하는 편이 낫다.
    throw new Error("CSRF 토큰을 못 받았다. 백엔드가 XSRF-TOKEN 쿠키를 안 내려준다");
  }
  return issued;
}

function readCookie(name: string): string | null {
  const found = document.cookie.split("; ").find((pair) => pair.startsWith(`${name}=`));

  return found ? decodeURIComponent(found.slice(name.length + 1)) : null;
}

/**
 * 오류 응답을 예외로 바꾼다.
 *
 * 본문이 `problem+json` 이 아닐 수도 있다. 프록시가 못 붙었거나 서버가 죽으면 HTML 이 오는데,
 * 그때 파싱을 믿으면 진짜 원인 대신 파싱 오류가 보인다.
 */
export async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body = (await response.json()) as {
      type?: string;
      detail?: string;
      trace_id?: string;
    };

    return new ApiError(
      response.status,
      body.type ?? "about:blank",
      body.detail ?? "요청을 처리하지 못했습니다.",
      body.trace_id,
    );
  } catch {
    return new ApiError(response.status, "about:blank", `서버가 ${response.status} 로 답했습니다.`);
  }
}
