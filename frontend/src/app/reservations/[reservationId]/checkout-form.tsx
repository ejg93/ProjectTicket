"use client";

import { useRouter } from "next/navigation";
import { useRef, useState } from "react";

import { Field } from "@/components/field";
import { SubmitButton } from "@/components/submit-button";
import { ApiError, api } from "@/lib/api";
import { money } from "@/lib/format";

type PaymentResult = {
  payment_id: number;
  reservation_id: number;
  status: string;
  amount: number;
  approval_number: string | null;
  decline_reason: string | null;
};

/** 서버가 준 오류를 화면 문구로 옮긴다. `slug` 로 갈린다(`D5`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }

  switch (error.slug) {
    case "hold-expired":
      return "선점 시간이 지나 좌석이 풀렸습니다. 좌석을 다시 골라 주세요.";
    case "invalid-transition":
      return "이미 처리된 예매입니다. 예매 내역을 확인해 주세요.";
    case "validation-failed":
      return "카드번호를 다시 확인해 주세요.";
    default:
      return "결제하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}

/**
 * 모의 결제(`19`).
 *
 * **거절은 예외가 아니다.** 서버가 201 에 `status: "declined"` 로 준다(`D5`) — 요청은 성공했고 결과가
 * 거절인 것이라 화면도 오류가 아니라 **결과**로 그린다. 예외로 다루면 「서버가 고장났다」로 읽힌다.
 *
 * **멱등키를 쥔다**(`D4`). 승인 응답을 못 받고 다시 누르면 같은 키라야 서버가 **두 번 긁지 않는다** —
 * 카드번호를 고쳐서 다시 낼 때는 다른 요청이라 키도 새로 만든다.
 */
export function CheckoutForm({ reservationId, amount }: { reservationId: number; amount: number }) {
  const [error, setError] = useState<string | null>(null);
  const [declined, setDeclined] = useState<string | null>(null);
  const idempotencyKey = useRef<string | null>(null);
  const lastCard = useRef<string | null>(null);
  const router = useRouter();

  async function submit(form: FormData) {
    setError(null);
    setDeclined(null);

    const cardNumber = String(form.get("card_number") ?? "");
    if (cardNumber !== lastCard.current) {
      idempotencyKey.current = null;
      lastCard.current = cardNumber;
    }
    idempotencyKey.current ??= crypto.randomUUID();

    try {
      const result = await api<PaymentResult>(`/api/reservations/${reservationId}/payments`, {
        method: "POST",
        body: { card_number: cardNumber },
        idempotencyKey: idempotencyKey.current,
      });

      if (result.status === "declined") {
        // 다시 낼 수 있게 키를 버린다 — 거절은 끝난 시도라 재전송이 아니다.
        idempotencyKey.current = null;
        setDeclined("카드사에서 결제를 거절했습니다. 다른 카드로 시도해 주세요.");
        return;
      }

      // 승인됐다. 상태와 티켓은 서버 컴포넌트가 다시 읽는다.
      router.refresh();
    } catch (thrown) {
      setError(messageOf(thrown));
    }
  }

  return (
    <form action={submit}>
      <Field name="card_number" label="카드번호" autoComplete="cc-number" />

      {/* **제출 버튼 위, 입력칸 아래다**(`D17`) */}
      {declined ? (
        <p className="error" role="alert">
          {declined}
        </p>
      ) : null}
      {error ? (
        <p className="error" role="alert">
          {error}
        </p>
      ) : null}

      <SubmitButton label={`${money(amount)} 결제`} pendingLabel="결제하는 중입니다" />
    </form>
  );
}
