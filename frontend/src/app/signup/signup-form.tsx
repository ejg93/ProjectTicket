"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { Field } from "@/components/field";
import { SubmitButton } from "@/components/submit-button";
import { ApiError, api } from "@/lib/api";

/** 동의 항목. 서버 JSON 이 snake_case 라 **받은 그대로 적는다**(`D5`) */
export type ConsentItem = {
  consent_item_id: number;
  code: string;
  title: string;
  required: boolean;
  sort_no: number;
};

type SignupResponse = { account_id: number };

/** 서버가 준 오류를 화면 문구로 옮긴다. `slug` 로 갈린다(`D5`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }

  switch (error.slug) {
    case "email-taken":
      return "이미 가입된 이메일입니다. 로그인해 주세요.";
    case "required-consent-missing":
      return "필수 항목에 모두 동의해 주세요.";
    case "validation-failed":
      // 비밀번호 규칙(NIST 15자·블록리스트)이 여기로 온다. 서버 문구를 그대로 안 쓴다(`D17`).
      return "입력하신 내용을 다시 확인해 주세요. 비밀번호는 15자 이상이어야 합니다.";
    default:
      return "가입하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}

export function SignupForm({ items }: { items: ConsentItem[] }) {
  const [error, setError] = useState<string | null>(null);
  const router = useRouter();

  async function submit(form: FormData) {
    setError(null);

    // 체크 안 된 상자는 `FormData` 에 아예 안 실린다. **빠진 것과 거부한 것이 같아 보이면 안 돼서**
    // 항목 목록을 돌며 `false` 를 채운다 — 서버는 항목마다 답을 요구한다(`consents` 가 `@NotNull`).
    const consents = Object.fromEntries(
      items.map((item) => [item.code, form.get(`consent-${item.code}`) === "on"]),
    );

    try {
      await api<SignupResponse>("/api/auth/signup", {
        method: "POST",
        body: {
          email: form.get("email"),
          password: form.get("password"),
          display_name: form.get("display_name"),
          consents,
        },
      });

      // 가입은 로그인이 아니다. 로그인 화면으로 보내고 왜 왔는지 말한다(`D17`).
      // 가입 화면을 뒤로가기에 안 남긴다 — 돌아가 봐야 같은 이메일로는 `email-taken` 이다.
      router.replace("/login?reason=signed-up");
    } catch (thrown) {
      setError(messageOf(thrown));
    }
  }

  return (
    <form action={submit}>
      <Field name="email" label="이메일" type="email" autoComplete="email" />
      <Field name="display_name" label="이름" autoComplete="name" />
      <Field name="password" label="비밀번호" type="password" autoComplete="new-password" />
      <p className="muted">비밀번호는 15자 이상으로 정해 주세요.</p>

      <fieldset>
        <legend>약관 동의</legend>
        {items.map((item) => (
          <p key={item.code}>
            <label htmlFor={`consent-${item.code}`}>
              <input
                id={`consent-${item.code}`}
                name={`consent-${item.code}`}
                type="checkbox"
                required={item.required}
              />{" "}
              {item.title}
              {item.required ? " (필수)" : " (선택)"}
            </label>
          </p>
        ))}
      </fieldset>

      {/* **제출 버튼 위, 입력칸 아래다**(`D17`). 방금 누른 자리 바로 옆이라야 보인다 */}
      {error ? (
        <p className="error" role="alert">
          {error}
        </p>
      ) : null}

      <SubmitButton label="가입" pendingLabel="가입하는 중입니다" />
    </form>
  );
}
