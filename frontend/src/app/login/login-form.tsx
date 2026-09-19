"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { Field } from "@/components/field";
import { SubmitButton } from "@/components/submit-button";
import { ApiError, api } from "@/lib/api";

/** 로그인 응답. 서버 JSON 이 snake_case 라 **받은 그대로 적는다**(`D5`) */
type LoginResponse = {
  account_id: number;
  email: string;
  role: string;
};

/** 서버가 준 오류를 화면 문구로 옮긴다. `slug` 로 갈린다(`D5`·`D17` 「서버 문구를 그대로 안 쓴다」) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }

  switch (error.slug) {
    case "login-failed":
      // 없는 계정인지 틀린 비밀번호인지 가르지 않는다. 서버가 이미 한 문구로 내려주고,
      // 화면이 그것을 나누면 가입 여부를 물어보는 도구가 된다(`D9`).
      return "이메일 또는 비밀번호가 맞지 않습니다.";
    case "validation-failed":
      return "이메일과 비밀번호를 모두 입력해 주세요.";
    default:
      return "로그인하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}

export function LoginForm() {
  const [error, setError] = useState<string | null>(null);
  const router = useRouter();

  // **제출 중인지를 여기서 안 든다**(`D16`). `SubmitButton` 이 `useFormStatus` 로 읽는다.
  async function submit(form: FormData) {
    setError(null);

    try {
      await api<LoginResponse>("/api/auth/login", {
        method: "POST",
        body: {
          email: form.get("email"),
          password: form.get("password"),
        },
      });

      // **`replace` 다.** 로그인 화면을 뒤로가기에 남기면 이미 로그인한 사람이 그리로 돌아간다.
      //
      // 세션 쿠키가 HttpOnly 라 서버만 읽는다. 로그인한 사실이 화면에 반영되려면 서버 컴포넌트가
      // 새 쿠키로 다시 그려져야 하고, 그것을 하는 것이 `refresh` 다 — 라우터 캐시에 로그인 전 판이
      // 남아 있으면 이동해도 그 판이 보인다. 이식 원본은 여기서 통짜 이동(`location.assign`)을 썼는데
      // `eslint-config-next` 가 그것을 경고로 막는다.
      router.replace("/me");
      router.refresh();
    } catch (thrown) {
      setError(messageOf(thrown));
    }
  }

  return (
    <form action={submit}>
      <Field name="email" label="이메일" type="email" autoComplete="email" />
      <Field name="password" label="비밀번호" type="password" autoComplete="current-password" />

      {/*
        어느 칸도 지목하지 않는 오류다(`D17`). 서버가 없는 계정인지 틀린 비밀번호인지 일부러 안 알려준다 —
        칸에 `aria-invalid` 를 걸면 맞은 이메일까지 「잘못된 입력」이라고 낭독된다.
        **자리는 제출 버튼 위, 입력칸 아래다** — 위에 두면 스크롤한 화면에서 안 보인다.
      */}
      {error ? (
        <p className="error" role="alert">
          {error}
        </p>
      ) : null}

      <SubmitButton label="로그인" pendingLabel="로그인하는 중입니다" />
    </form>
  );
}
