"use client";

import { useFormStatus } from "react-dom";

/**
 * 제출 중인지를 **폼이 알려준다**(`D16` 「폼이 제출 중인지를 손으로 들지 않는다」).
 *
 * 부르는 쪽이 `useState` 로 들면 안 맞는다 — 폼 `action` 은 전환(transition) 안에서 돌아서
 * 그 안의 `setState` 가 액션이 끝날 때까지 화면에 안 비친다. `useFormStatus` 는 그 전환을 보고 있다.
 *
 * **이 컴포넌트가 폼 안에 있어야 읽힌다.** 폼 자신에서 부르면 `pending` 이 언제나 거짓이다.
 */
export function SubmitButton({ label, pendingLabel }: { label: string; pendingLabel: string }) {
  const { pending } = useFormStatus();

  return (
    <button type="submit" disabled={pending}>
      {pending ? pendingLabel : label}
    </button>
  );
}
