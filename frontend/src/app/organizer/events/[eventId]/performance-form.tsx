"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { Field } from "@/components/field";
import { SubmitButton } from "@/components/submit-button";
import { ApiError, api } from "@/lib/api";

import { kstIso, type Hall } from "../../types";

/** 서버가 준 오류를 화면 문구로 옮긴다. `slug` 로 갈린다(`D5`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
  switch (error.slug) {
    case "performance-slot-taken":
      return "그 홀의 그 시각에 이미 회차가 있습니다. 다른 시각을 골라 주세요.";
    case "validation-failed":
      return "입력을 다시 확인해 주세요. 판매 시작은 관람 시각보다 앞이어야 합니다.";
    case "event-not-found":
      return "공연을 찾을 수 없습니다.";
    default:
      return "회차를 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}

/**
 * 회차 등록(`45b`). 시각은 **KST 로 받아 오프셋을 붙여 보낸다**(`D7`) — `datetime-local` 은 시간대가 없어서 그대로 보내면 서버가 400 이다.
 * 판매 마감은 안 받는다 — 서버가 `관람 − 1시간` 으로 채운다(`V14`).
 */
export function PerformanceForm({ eventId, halls }: { eventId: number; halls: Hall[] }) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);

  async function submit(form: FormData) {
    setError(null);
    try {
      await api(`/api/organizer/events/${eventId}/performances`, {
        method: "POST",
        body: {
          hall_id: Number(form.get("hall_id")),
          starts_at: kstIso(String(form.get("starts_at"))),
          sales_open_at: kstIso(String(form.get("sales_open_at"))),
        },
      });
      router.refresh();
    } catch (thrown) {
      setError(messageOf(thrown));
    }
  }

  return (
    <form action={submit} aria-label="회차 등록">
      <p>
        <label htmlFor="hall_id">홀</label>
        <select id="hall_id" name="hall_id" required>
          {halls.map((h) => (
            <option key={h.hall_id} value={h.hall_id}>
              {h.venue_name} {h.name}
            </option>
          ))}
        </select>
      </p>
      <Field name="starts_at" label="관람 시각(한국 시간)" type="datetime-local" />
      <Field name="sales_open_at" label="판매 시작(한국 시간)" type="datetime-local" />
      {error ? (
        <p className="error" role="alert">
          {error}
        </p>
      ) : null}
      <SubmitButton label="회차 등록" pendingLabel="등록하는 중입니다" />
    </form>
  );
}
