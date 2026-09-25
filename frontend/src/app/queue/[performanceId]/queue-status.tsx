"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import { dropAdmission, saveAdmission } from "@/lib/admission";
import { ApiError, api } from "@/lib/api";

/** `QueueService.Position` — 서버 JSON 이 snake_case 라 **받은 그대로 적는다**(`D5`). 무엇이 들었는지는 `state` 가 정한다 */
type Position = {
  state: "waiting" | "admitted";
  rank?: number | null;
  eta_seconds?: number | null;
  admission_token?: string | null;
};

/** ADR 0003 — 폴링 2초. 이 호출이 하트비트를 겸한다(서버 90초) */
export const POLL_MS = 2000;

/** 서버가 준 오류를 화면 문구로 옮긴다. `slug` 로 갈린다(`D5`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
  switch (error.slug) {
    case "queue-closed":
      return "판매가 끝났거나 닫힌 회차입니다.";
    case "not-in-queue":
      // 탭을 오래 숨겨 두면 하트비트가 끊겨 줄에서 빠진다(24).
      return "대기열에서 빠졌습니다. 다시 줄을 서 주세요.";
    case "queue-unavailable":
      return "대기열을 잠시 쓸 수 없습니다. 잠시 후 다시 시도해 주세요.";
    default:
      return "대기열 상태를 받지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}

/** 예상 대기를 분으로. 1분이 안 되면 「1분 이내」 — 초를 세면 폴링마다 숫자가 튄다 */
function etaText(seconds: number | null | undefined): string | null {
  if (seconds == null) return null;
  return seconds < 60 ? "1분 이내" : `약 ${Math.ceil(seconds / 60)}분`;
}

/**
 * 대기열의 지금(`43`, `D12`). 들어오면 줄에 서고(POST) 2초마다 순번을 묻는다(GET — 하트비트를 겸한다).
 *
 * **탭이 숨어도 계속 묻는다** — 이 폴링이 하트비트라(24) 멈추면 90초 뒤 줄에서 빠진다. 줄은 떠난 사람만 빼야 한다(마무리 13차 독립 리뷰).
 * 브라우저가 숨은 탭의 타이머를 늦춰도(크롬은 5분 뒤 1분에 한 번) 90초 안이다. 돌아오면 바로 한 번 묻는다.
 * **입장하면** 토큰을 이 탭에 두고(`lib/admission.ts`) 좌석 화면으로 간다 — 선점이 그 토큰을 헤더로 싣는다.
 */
export function QueueStatus({ performanceId }: { performanceId: string }) {
  const router = useRouter();
  const [position, setPosition] = useState<Position | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const timer = useRef<ReturnType<typeof setInterval> | undefined>(undefined);

  useEffect(() => {
    // `live` 는 이 효과가 살아 있고 아직 실패하지 않았다는 뜻이다. 실패하면 폴링도 되살리기도 멈춘다.
    let live = true;
    let started = false;
    const path = `/api/queue/${performanceId}`;
    const stop = () => {
      clearInterval(timer.current);
      timer.current = undefined;
    };
    const settle = (next: Position) => {
      if (!live) return;
      if (next.state === "admitted" && next.admission_token) {
        live = false;
        stop();
        saveAdmission(performanceId, next.admission_token);
        router.replace(`/performances/${performanceId}`);
        return;
      }
      setPosition(next);
    };
    const fail = (thrown: unknown) => {
      if (!live) return;
      live = false;
      stop();
      setError(messageOf(thrown));
    };
    const poll = () => api<Position>(path).then(settle, fail);
    // 돌아오면 바로 한 번 묻는다 — 숨은 동안 브라우저가 타이머를 늦췄을 수 있다. 줄에 서기 전(첫 POST 전)에는 안 묻는다.
    const onVisibility = () => {
      if (live && started && document.visibilityState === "visible") void poll();
    };

    api<Position>(path, { method: "POST" }).then((first) => {
      settle(first);
      if (live && first.state === "waiting") {
        started = true;
        timer.current = setInterval(poll, POLL_MS);
      }
    }, fail);
    document.addEventListener("visibilitychange", onVisibility);

    return () => {
      live = false;
      stop();
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [performanceId, router, attempt]);

  async function leave() {
    clearInterval(timer.current);
    timer.current = undefined;
    try {
      await api<void>(`/api/queue/${performanceId}`, { method: "DELETE" });
    } catch {
      // 줄에 없어도 204 다. 네트워크가 끊겼으면 하트비트가 끊겨 서버가 빼 준다(24)
    }
    dropAdmission(performanceId);
    router.push("/events");
  }

  if (error) {
    return (
      <div>
        <p className="error" role="alert">
          {error}
        </p>
        <p>
          <button
            type="button"
            onClick={() => {
              // 오류를 지우고 효과를 다시 돌린다 — 효과 안에서 지우면 렌더가 한 번 더 돈다(`react-hooks`)
              setError(null);
              setAttempt((n) => n + 1);
            }}
          >
            다시 줄 서기
          </button>
        </p>
      </div>
    );
  }

  const eta = etaText(position?.eta_seconds);

  return (
    <div>
      <p role="status">
        {position?.rank != null ? `대기 순서 ${position.rank}번째` : "대기열에 들어가는 중입니다"}
        {eta ? ` · 예상 대기 ${eta}` : null}
      </p>
      <p className="muted">차례가 되면 좌석 선택 화면으로 자동으로 넘어갑니다. 이 창을 닫으면 순서가 사라집니다.</p>
      <p>
        <button type="button" onClick={leave}>
          줄 나가기
        </button>
      </p>
    </div>
  );
}
