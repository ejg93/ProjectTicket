"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { SubmitButton } from "@/components/submit-button";
import { ApiError, api } from "@/lib/api";

/** 서버가 준 오류를 화면 문구로 옮긴다. `slug` 로 갈린다(`D5`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
  switch (error.slug) {
    case "performance-not-openable":
      return "열 수 없는 회차입니다. 홀의 모든 구역에 등급이 붙어 있는지 확인해 주세요.";
    case "invalid-transition":
      return "지금 상태에서는 할 수 없습니다. 화면을 새로 고쳐 주세요.";
    case "performance-not-found":
      return "회차를 찾을 수 없습니다.";
    default:
      return "처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}

/**
 * 회차 하나의 조작(`45b`). `draft` 는 「오픈」, `open` 은 「취소」.
 * **취소는 두 단계다** — 회차 취소는 그 회차의 모든 예매를 전액 환불한다(`17a`). 한 번 누름으로 끝나면 실수가 돈이 된다.
 */
export function PerformanceActions({ performanceId, status }: { performanceId: number; status: string }) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  async function run(action: "open" | "cancel") {
    setError(null);
    try {
      await api(`/api/organizer/performances/${performanceId}/${action}`, { method: "POST" });
      setConfirming(false);
      router.refresh();
    } catch (thrown) {
      setError(messageOf(thrown));
    }
  }

  const alert = error ? (
    <p className="error" role="alert">
      {error}
    </p>
  ) : null;

  if (status === "draft") {
    return (
      <form action={() => run("open")} aria-label="회차 오픈">
        {alert}
        <SubmitButton label="오픈" pendingLabel="여는 중입니다" />
      </form>
    );
  }

  if (status !== "open") {
    return null;
  }

  if (!confirming) {
    return (
      <div>
        {alert}
        <button type="button" onClick={() => setConfirming(true)}>
          회차 취소
        </button>
      </div>
    );
  }

  return (
    <form action={() => run("cancel")} aria-label="회차 취소 확인">
      <p>이 회차의 모든 예매를 전액 환불하고 판매를 닫습니다. 되돌릴 수 없습니다.</p>
      {alert}
      <SubmitButton label="모두 환불하고 취소" pendingLabel="취소하는 중입니다" />
      <button type="button" onClick={() => setConfirming(false)}>
        그만두기
      </button>
    </form>
  );
}
