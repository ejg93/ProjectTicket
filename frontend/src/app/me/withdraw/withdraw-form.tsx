"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { Field } from "@/components/field";
import { SubmitButton } from "@/components/submit-button";
import { ApiError, api } from "@/lib/api";

/** 서버가 준 오류를 화면 문구로 옮긴다. `slug` 로 갈린다(`D5`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
  switch (error.slug) {
    case "login-failed":
      // 여러 번 틀려 잠겨도 같은 이름이다 — 잠겼다고 따로 말하지 않는다(`D9`)
      return "비밀번호가 맞지 않습니다.";
    case "validation-failed":
      return "비밀번호를 입력해 주세요.";
    case "account-not-found":
      return "이미 탈퇴한 계정입니다.";
    default:
      return "탈퇴하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}

/**
 * 탈퇴(`44c`). **비밀번호를 다시 받는다** — 열린 세션만으로 계정을 없애면 자리를 비운 사이 누가 누른다.
 * 틀리면 로그인과 같은 401 `login-failed` 가 오고, `api()` 는 그것을 로그인 화면으로 안 보내고 던진다.
 * 성공하면 서버가 세션을 끊었으니 처음 화면으로 가서 다시 그린다.
 */
export function WithdrawForm() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);

  async function submit(form: FormData) {
    setError(null);
    try {
      await api<void>("/api/me", { method: "DELETE", body: { password: form.get("password") } });
      router.replace("/");
      router.refresh();
    } catch (thrown) {
      setError(messageOf(thrown));
    }
  }

  return (
    <form action={submit}>
      <Field name="password" label="비밀번호" type="password" autoComplete="current-password" />
      {error ? (
        <p className="error" role="alert">
          {error}
        </p>
      ) : null}
      <SubmitButton label="탈퇴하기" pendingLabel="탈퇴하는 중입니다" />
    </form>
  );
}
