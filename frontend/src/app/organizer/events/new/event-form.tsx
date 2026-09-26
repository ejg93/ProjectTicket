"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { Field } from "@/components/field";
import { SubmitButton } from "@/components/submit-button";
import { ApiError, api } from "@/lib/api";

import type { Organizer } from "../../types";

/** 등급 줄을 가리키는 칸(`grades[1].code`)을 줄 번호와 칸으로 가른다(`45d-a` 가 싣는 꼴) */
const GRADE_FIELD = /^grades\[(\d+)\]\.(code|sections)$/;

/**
 * 틀린 칸이 등급 줄이면 그 줄을 짚는 문구. 아니면 null — 슬러그 문구로 떨어진다.
 * **같은 칸 이름을 두 원인이 낸다** — 형식(Bean Validation: 코드 꼴·구역 없음)과 DB 제약(코드 중복·한 구역에 등급 둘, `45d-a`).
 * 칸 이름만으로는 못 가르니 문구가 둘 다 말한다.
 */
function gradeMessageOf(error: ApiError): string | null {
  for (const { field } of error.errors) {
    const match = GRADE_FIELD.exec(field);
    if (!match) continue;
    const row = Number(match[1]) + 1;
    return match[2] === "code"
      ? `${row}번째 등급의 코드를 확인해 주세요. 영문 대문자로 시작해야 하고, 앞 등급과 겹칠 수 없습니다.`
      : `${row}번째 등급의 구역을 확인해 주세요. 하나 이상 골라야 하고, 앞 등급이 고른 구역은 고를 수 없습니다.`;
  }
  return null;
}

/** 서버가 준 오류를 화면 문구로 옮긴다. `slug` 로 갈린다(`D5`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
  switch (error.slug) {
    case "validation-failed":
      return (
        gradeMessageOf(error) ??
        "입력을 다시 확인해 주세요. 등급 코드는 영문 대문자로 시작하고, 등급마다 구역을 하나 이상 골라야 합니다."
      );
    case "organizer-not-found":
      return "이 기획사로 등록할 권한이 없습니다.";
    default:
      return "공연을 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}

/** 제출한 값. 서버가 거절하면 이것으로 칸을 다시 채운다(`39-4`). 가격은 칸 글자 그대로 — 숫자로 바꾸면 빈 칸이 0 이 된다 */
type Draft = { organizerId: string; title: string; grades: { code: string; price: string; sections: string[] }[] };

/**
 * 공연·등급 등록(`45b`). 등급 줄은 늘릴 수 있고, 줄마다 코드·가격·구역을 받는다 — 구역은 홀 목록의 코드에서 고른다(`45a-1`).
 * 등급 없는 공연은 서버가 400 으로 막는다(회차를 못 연다). 성공하면 그 공연 화면으로 간다.
 *
 * **서버가 거절해도 친 값을 남긴다**(`39-4`). React 19 는 액션이 끝나면 `form.reset()` 을 부른다 — 오류를 잡고 끝나도 같다.
 * 제출 때 값을 `draft` 에 들고 칸마다 `defaultValue`·`defaultChecked` 로 돌려준다. reset 이 그 값으로 되돌린다.
 */
export function EventForm({ organizers, sections }: { organizers: Organizer[]; sections: string[] }) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [grades, setGrades] = useState(1);
  const [draft, setDraft] = useState<Draft | null>(null);

  /** 뺀 줄의 값을 `draft` 에서도 걷는다 — 안 걷으면 다시 「등급 추가」한 줄이 뺀 줄의 값으로 되살아난다(마무리 17차) */
  function removeLastGrade() {
    setGrades((n) => n - 1);
    setDraft((d) => (d ? { ...d, grades: d.grades.slice(0, grades - 1) } : d));
  }

  async function submit(form: FormData) {
    setError(null);
    const rows = Array.from({ length: grades }, (_, i) => ({
      code: String(form.get(`grade_code_${i}`) ?? ""),
      price: Number(form.get(`grade_price_${i}`) ?? 0),
      sections: form.getAll(`grade_sections_${i}`).map(String),
    }));
    // 구역 체크박스는 같은 이름이 여럿이라 `getAll` 이다 — `get` 은 첫 하나만 준다.
    setDraft({
      organizerId: String(form.get("organizer_id") ?? ""),
      title: String(form.get("title") ?? ""),
      grades: rows.map((row, i) => ({ code: row.code, price: String(form.get(`grade_price_${i}`) ?? ""), sections: row.sections })),
    });
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
        {/* `<select>` 는 마운트 뒤의 `defaultValue` 변경을 옵션에 안 옮긴다(React 19) — 제출마다 `key` 로 다시 그려 그 값을 기본으로 만든다 */}
        <select key={draft?.organizerId} id="organizer_id" name="organizer_id" required defaultValue={draft?.organizerId}>
          {organizers.map((o) => (
            <option key={o.organizer_id} value={o.organizer_id}>
              {o.name}
            </option>
          ))}
        </select>
      </p>
      <Field name="title" label="공연 제목" maxLength={200} defaultValue={draft?.title} />
      {Array.from({ length: grades }, (_, i) => (
        <fieldset key={i}>
          <legend>등급 {i + 1}</legend>
          <Field name={`grade_code_${i}`} label="등급 코드(예: VIP)" defaultValue={draft?.grades[i]?.code} />
          <Field name={`grade_price_${i}`} label="가격(원)" type="number" defaultValue={draft?.grades[i]?.price} />
          <p>구역</p>
          {sections.map((code) => (
            <label key={code}>
              <input
                type="checkbox"
                name={`grade_sections_${i}`}
                value={code}
                defaultChecked={draft?.grades[i]?.sections.includes(code) ?? false}
              /> {code}
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
            <button type="button" onClick={removeLastGrade}>
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
