"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { Field } from "@/components/field";
import { SubmitButton } from "@/components/submit-button";
import { ApiError, api } from "@/lib/api";

import type { Organizer } from "../../types";

/** 서버가 준 오류를 화면 문구로 옮긴다. `slug` 로 갈린다(`D5`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
  switch (error.slug) {
    case "validation-failed":
      return "입력을 다시 확인해 주세요. 등급 코드는 영문 대문자로 시작하고, 등급마다 구역을 하나 이상 골라야 합니다.";
    case "organizer-not-found":
      return "이 기획사로 등록할 권한이 없습니다.";
    default:
      return "공연을 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}

/**
 * 공연·등급 등록(`45b`). 등급 줄은 늘릴 수 있고, 줄마다 코드·가격·구역을 받는다 — 구역은 홀 목록의 코드에서 고른다(`45a-1`).
 * 등급 없는 공연은 서버가 400 으로 막는다(회차를 못 연다). 성공하면 그 공연 화면으로 간다.
 */
export function EventForm({ organizers, sections }: { organizers: Organizer[]; sections: string[] }) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [grades, setGrades] = useState(1);

  async function submit(form: FormData) {
    setError(null);
    const rows = Array.from({ length: grades }, (_, i) => ({
      code: String(form.get(`grade_code_${i}`) ?? ""),
      price: Number(form.get(`grade_price_${i}`) ?? 0),
      sections: form.getAll(`grade_sections_${i}`).map(String),
    }));
    try {
      const created = await api<{ event_id: number }>("/api/organizer/events", {
        method: "POST",
        body: { organizer_id: Number(form.get("organizer_id")), title: form.get("title"), grades: rows },
      });
      router.push(`/organizer/events/${created.event_id}`);
    } catch (thrown) {
      setError(messageOf(thrown));
    }
  }

  return (
    <form action={submit}>
      <p>
        <label htmlFor="organizer_id">기획사</label>
        <select id="organizer_id" name="organizer_id" required>
          {organizers.map((o) => (
            <option key={o.organizer_id} value={o.organizer_id}>
              {o.name}
            </option>
          ))}
        </select>
      </p>
      <Field name="title" label="공연 제목" />
      {Array.from({ length: grades }, (_, i) => (
        <fieldset key={i}>
          <legend>등급 {i + 1}</legend>
          <Field name={`grade_code_${i}`} label="등급 코드(예: VIP)" />
          <Field name={`grade_price_${i}`} label="가격(원)" type="number" />
          <p>구역</p>
          {sections.map((code) => (
            <label key={code}>
              <input type="checkbox" name={`grade_sections_${i}`} value={code} /> {code}
            </label>
          ))}
        </fieldset>
      ))}
      <p>
        <button type="button" onClick={() => setGrades((n) => n + 1)}>
          등급 추가
        </button>
        {/* 칸이 전부 필수라 잘못 늘린 빈 줄이 제출을 영영 막는다 — 마지막 줄을 뺄 수 있어야 한다(마무리 14차). 끝 줄만 빼 번호가 안 비게 한다 */}
        {grades > 1 ? (
          <>
            {" "}
            <button type="button" onClick={() => setGrades((n) => n - 1)}>
              마지막 등급 빼기
            </button>
          </>
        ) : null}
      </p>
      {error ? (
        <p className="error" role="alert">
          {error}
        </p>
      ) : null}
      <SubmitButton label="공연 등록" pendingLabel="등록하는 중입니다" />
    </form>
  );
}
