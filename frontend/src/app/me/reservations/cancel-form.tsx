"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { SubmitButton } from "@/components/submit-button";
import { ApiError, api } from "@/lib/api";
import { money } from "@/lib/format";

/** `GET /api/reservations/{id}/refund-preview`(`44a`). 서버 JSON 이 snake_case 라 **받은 그대로 적는다**(`D5`) */
type Preview = {
  cancellable: boolean;
  reason: string | null;
  payment_amount: number | null;
  days_before: number;
  tier_rate: number | null;
  fee_amount: number | null;
  refund_amount: number | null;
};

/** 서버가 준 오류를 화면 문구로 옮긴다. `slug` 로 갈린다(`D5`). 미리보기의 `reason` 도 같은 슬러그라 같은 표를 쓴다 */
function messageOf(slug: string | null): string {
  switch (slug) {
    case "cancel-window-closed":
      // 서버는 관람일 당일과 지난 공연을 같은 이름으로 막는다(구간이 없다)
      return "관람일 당일이거나 지난 공연은 취소할 수 없습니다.";
    case "invalid-transition":
      return "지금 상태에서는 취소할 수 없습니다.";
    case "reservation-not-found":
      return "예매를 찾을 수 없습니다.";
    case "quote-changed":
      return "환불 금액이 바뀌었습니다. 다시 확인해 주세요.";
    default:
      return "취소하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}

const slugOf = (thrown: unknown) => (thrown instanceof ApiError ? thrown.slug : null);

/**
 * 예매 취소 — **두 단계다**(`44b`, `D6` 고지). 「취소」가 미리보기를 받아 결제액·수수료·환불액을 보여 주고,
 * 「이 금액으로 취소」가 그제야 `POST …/cancel` 을 보낸다. 한 번에 취소하면 수수료를 못 보고 누른다.
 *
 * 미리보기와 실제 환불은 서버의 같은 함수다(`RefundQuote`). **같은 시각이면 같다** — 화면을 띄운 채 KST 자정을 넘기거나
 * 구간표 새 판이 효력을 얻으면 금액이 바뀐다. 그래서 **본 금액을 싣는다**(`44a-1a`) — 다르면 서버가 409 `quote-changed` 로
 * 아무것도 안 하고, 화면은 미리보기를 다시 받아 새 금액을 보여 준다.
 */
export function CancelForm({ reservationId }: { reservationId: number }) {
  const router = useRouter();
  const [preview, setPreview] = useState<Preview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  async function load() {
    setError(null);
    try {
      setPreview(await api<Preview>(`/api/reservations/${reservationId}/refund-preview`));
    } catch (thrown) {
      setError(messageOf(slugOf(thrown)));
    }
  }

  async function cancel() {
    setError(null);
    // 폼은 취소할 수 있을 때만 그려서 금액이 있다. 없으면 보낼 금액이 없으니 미리보기부터 다시 받는다
    if (preview?.refund_amount == null) return load();
    try {
      // 멱등키를 안 싣는다 — 취소는 조건부 UPDATE 라 둘째 요청이 0행이다(`D4`)
      await api(`/api/reservations/${reservationId}/cancel`, {
        method: "POST",
        body: { refund_amount: preview.refund_amount },
      });
      setDone(true);
      router.refresh();
    } catch (thrown) {
      const slug = slugOf(thrown);
      // 그사이 금액이 바뀌었다 — 새 금액을 보여 주고 다시 누르게 한다
      if (slug === "quote-changed") await load();
      setError(messageOf(slug));
    }
  }

  if (done) {
    return <p role="status">취소했습니다. 환불 금액은 결제한 카드로 돌아갑니다.</p>;
  }

  const alert = error ? (
    <p className="error" role="alert">
      {error}
    </p>
  ) : null;

  if (!preview) {
    return (
      <div>
        {alert}
        <button type="button" onClick={load}>
          취소
        </button>
      </div>
    );
  }

  if (!preview.cancellable) {
    return <p role="status">{messageOf(preview.reason)}</p>;
  }

  return (
    <form action={cancel} aria-label="취소 확인">
      <dl>
        <dt>결제 금액</dt>
        <dd>{preview.payment_amount != null ? money(preview.payment_amount) : null}</dd>
        <dt>관람일까지</dt>
        <dd>{preview.days_before}일</dd>
        <dt>취소 수수료</dt>
        <dd>
          {preview.fee_amount != null ? money(preview.fee_amount) : null}
          {preview.tier_rate != null ? ` (${Math.round(preview.tier_rate * 100)}%)` : null}
        </dd>
        <dt>환불 금액</dt>
        <dd>{preview.refund_amount != null ? money(preview.refund_amount) : null}</dd>
      </dl>
      {alert}
      <SubmitButton label="이 금액으로 취소" pendingLabel="취소하는 중입니다" />
    </form>
  );
}
