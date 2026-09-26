import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiError, api } from "./api";

/**
 * 서버를 부르는 입구가 **규약대로 부르나**(`D8` — 판정을 보는 자리다).
 *
 * jsdom 이라 진짜 프록시·세션은 못 밟는다. 여기서 보는 것은 **이 파일이 쥔 결정** 셋이다:
 * 표기를 안 바꾼다(`D5`), 안전하지 않은 메서드에 CSRF 를 싣는다, 오류를 `slug` 로 준다.
 */

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function mockFetch(response: Response) {
  const spy = vi.fn().mockResolvedValue(response);
  vi.stubGlobal("fetch", spy);
  return spy;
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

describe("api()", () => {
  it("보낸 본문의 키를 안 바꾼다", async () => {
    document.cookie = "XSRF-TOKEN=token-value";
    const spy = mockFetch(jsonResponse({ account_id: 1 }, 201));

    await api("/api/auth/signup", { method: "POST", body: { display_name: "홍길동" } });

    // 서버가 snake_case 를 받는다(`D5`). 카멜로 바꿔 보내면 그 칸만 조용히 비어서 들어간다.
    expect(JSON.parse(spy.mock.calls[0][1].body)).toEqual({ display_name: "홍길동" });
  });

  it("받은 응답의 키도 안 바꾼다", async () => {
    mockFetch(jsonResponse({ account_id: 7, display_name: "홍길동" }));

    const me = await api<{ account_id: number }>("/api/me");

    expect(me).toEqual({ account_id: 7, display_name: "홍길동" });
  });

  it("상태를 바꾸는 메서드에 CSRF 토큰을 싣는다", async () => {
    document.cookie = "XSRF-TOKEN=token-value";
    const spy = mockFetch(jsonResponse({}, 200));

    await api("/api/auth/login", { method: "POST", body: {} });

    expect(spy.mock.calls[0][1].headers["X-XSRF-TOKEN"]).toBe("token-value");
  });

  it("읽기에는 CSRF 토큰을 안 싣는다", async () => {
    document.cookie = "XSRF-TOKEN=token-value";
    const spy = mockFetch(jsonResponse({}));

    await api("/api/me");

    // 읽기에 실으면 서버가 안 보는 헤더를 매번 붙이는 것이고, 쿠키가 없을 때 괜히 한 번 더 두드린다.
    expect(spy.mock.calls[0][1].headers["X-XSRF-TOKEN"]).toBeUndefined();
  });

  it("오류를 접두어 뗀 이름으로 준다", async () => {
    document.cookie = "XSRF-TOKEN=token-value";
    mockFetch(
      jsonResponse(
        {
          type: "tag:projectticket.example,2026:seat-taken",
          detail: "이미 잡힌 좌석이 있다",
          trace_id: "abc",
        },
        409,
      ),
    );

    const thrown = await api("/api/reservations", { method: "POST", body: {} }).catch((e) => e);

    expect(thrown).toBeInstanceOf(ApiError);
    expect((thrown as ApiError).slug).toBe("seat-taken");
    expect((thrown as ApiError).traceId).toBe("abc");
  });

  it("검증 실패가 실은 칸을 읽고, 모양이 틀린 칸은 버린다(`45d-b`)", async () => {
    document.cookie = "XSRF-TOKEN=token-value";
    mockFetch(
      jsonResponse(
        {
          type: "tag:projectticket.example,2026:validation-failed",
          detail: "등급 코드가 겹친다",
          errors: [{ field: "grades[1].code", message: "등급 코드가 겹친다: VIP" }, { field: 3 }],
        },
        400,
      ),
    );

    const thrown = (await api("/api/organizer/events", { method: "POST", body: {} }).catch((e) => e)) as ApiError;

    expect(thrown.errors).toEqual([{ field: "grades[1].code", message: "등급 코드가 겹친다: VIP" }]);
  });

  it("세션이 없는 401(`unauthenticated`)은 던지지 않고 로그인으로 넘어가는 동안 멈춘다", async () => {
    mockFetch(jsonResponse({ type: "tag:projectticket.example,2026:unauthenticated", detail: "로그인 필요" }, 401));

    // 이동 자체는 jsdom 이 못 한다(`location` 을 못 갈아 끼운다). 대신 **안 던진다**를 잰다 — 아래 `login-failed` 예외를
    // 넓혀 이 슬러그까지 던지게 되면 부르는 폼이 오류 문구를 띄우고 로그인으로 안 간다(마무리 14차).
    const settled = await Promise.race([
      api("/api/me/reservations").then(
        () => "resolved",
        () => "rejected",
      ),
      new Promise((resolve) => setTimeout(() => resolve("pending"), 50)),
    ]);

    expect(settled).toBe("pending");
  });

  it("비밀번호가 틀린 401(`login-failed`)은 로그인으로 안 보내고 던진다(`44c`)", async () => {
    document.cookie = "XSRF-TOKEN=token-value";
    mockFetch(jsonResponse({ type: "tag:projectticket.example,2026:login-failed", detail: "틀렸다" }, 401));

    // 탈퇴 입구는 `/api/auth/` 밖이다. 세션이 멀쩡한데 로그인으로 튕기면 안 된다.
    const thrown = await api("/api/me", { method: "DELETE", body: { password: "x" } }).catch((e) => e);

    expect(thrown).toBeInstanceOf(ApiError);
    expect((thrown as ApiError).slug).toBe("login-failed");
  });

  it("남이 낸 problem+json 은 우리 이름으로 안 읽는다", async () => {
    mockFetch(jsonResponse({ type: "about:blank", detail: "Gateway Timeout" }, 504));

    const thrown = (await api("/api/me").catch((e) => e)) as ApiError;

    // 접두어가 다르면 슬러그가 빈 값이다. 화면이 `slug` 로 분기하므로 여기서 기본 문구로 떨어진다.
    expect(thrown.slug).toBe("");
  });

  it("본문이 problem+json 이 아니어도 예외를 만든다", async () => {
    mockFetch(new Response("<html>502</html>", { status: 502 }));

    const thrown = (await api("/api/me").catch((e) => e)) as ApiError;

    // 프록시가 못 붙으면 HTML 이 온다. 파싱을 믿으면 진짜 원인 대신 파싱 오류가 보인다.
    expect(thrown.status).toBe(502);
    expect(thrown.detail).toContain("502");
  });

  it("204 는 파싱하지 않는다", async () => {
    document.cookie = "XSRF-TOKEN=token-value";
    mockFetch(new Response(null, { status: 204 }));

    await expect(api("/api/me", { method: "DELETE" })).resolves.toBeUndefined();
  });

  it("멱등키를 주면 헤더로 싣는다", async () => {
    document.cookie = "XSRF-TOKEN=token-value";
    const spy = mockFetch(jsonResponse({}));

    await api("/api/reservations", { method: "POST", body: {}, idempotencyKey: "key-1" });

    expect(spy.mock.calls[0][1].headers["Idempotency-Key"]).toBe("key-1");
  });
});
