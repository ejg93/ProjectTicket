"use client";

import { useRouter } from "next/navigation";
import { useRef, useState } from "react";

import { SeatMap, type SeatMapData } from "@/components/seat-map";
import { SubmitButton } from "@/components/submit-button";
import { ApiError, api } from "@/lib/api";

type Reservation = { reservation_id: number };

/** 서버가 준 오류를 화면 문구로 옮긴다. `slug` 로 갈린다(`D5`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }

  switch (error.slug) {
    case "seat-taken":
      // 고른 사이에 남이 먼저 잡았다. 좌석도를 다시 받아야 해서 새로고침을 권한다.
      return "방금 다른 분이 먼저 잡은 좌석이 있습니다. 좌석 현황을 새로 고쳐 주세요.";
    case "over-limit":
      return "한 번에 잡을 수 있는 좌석 수를 넘었습니다.";
    case "duplicate-hold":
      return "이 회차에 진행 중인 예매가 이미 있습니다. 그 예매를 먼저 마쳐 주세요.";
    case "performance-not-open":
      return "지금은 예매할 수 없는 회차입니다.";
    case "admission-required":
      // 대기열이 켜진 회차다. 줄을 서는 화면은 `43` 이 붙인다.
      return "대기 인원이 많아 순서를 기다려야 합니다. 잠시 후 다시 시도해 주세요.";
    // `unauthenticated` 를 여기서 안 본다 — 401 은 `api.ts` 가 로그인 화면으로 보내고 예외를 안 던진다.
    // 적어 두면 안 닿는 분기가 되고, 다음 사람이 그 자리를 고치면서 있는 줄 안다.
    default:
      return "좌석을 잡지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}

/**
 * 좌석을 골라 선점까지.
 *
 * **멱등키를 이 컴포넌트가 쥔다**(`D4` 「멱등키」). 실패해서 다시 누를 때 **같은 값**을 보내야 서버가
 * 재전송으로 본다 — 누를 때마다 새로 만들면 응답을 못 받은 첫 요청이 남긴 선점이 그대로 있고
 * 둘째 요청이 `duplicate-hold` 로 막힌다. 좌석을 바꾸면 **다른 요청**이라 키도 새로 만든다.
 */
export function HoldForm({ data, performanceId }: { data: SeatMapData; performanceId: string }) {
  const [selected, setSelected] = useState<number[]>([]);
  const [error, setError] = useState<string | null>(null);
  const idempotencyKey = useRef<string | null>(null);
  const router = useRouter();

  function pick(seatIds: number[]) {
    setSelected(seatIds);
    // 고른 좌석이 달라지면 **다른 요청**이다. 앞의 키를 그대로 쓰면 서버가 앞 요청의 답을 돌려준다.
    idempotencyKey.current = null;
    setError(null);
  }

  async function submit() {
    if (selected.length === 0) {
      setError("좌석을 먼저 골라 주세요.");
      return;
    }
    setError(null);

    idempotencyKey.current ??= crypto.randomUUID();

    try {
      const reservation = await api<Reservation>(`/api/performances/${performanceId}/reservations`, {
        method: "POST",
        body: { seat_ids: selected },
        idempotencyKey: idempotencyKey.current,
      });
      router.push(`/reservations/${reservation.reservation_id}`);
    } catch (thrown) {
      setError(messageOf(thrown));
    }
  }

  return (
    <form action={submit}>
      <SeatMap data={data} onChange={pick} />

      {/* **제출 버튼 위, 입력칸 아래다**(`D17`) */}
      {error ? (
        <p className="error" role="alert">
          {error}
        </p>
      ) : null}

      <SubmitButton label="선택한 좌석 예매" pendingLabel="좌석을 잡는 중입니다" />
    </form>
  );
}
