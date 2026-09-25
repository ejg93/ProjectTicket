/**
 * 예매 상태의 화면 이름(`44b`, `D3` 예매 상태기계). **내부 값을 그대로 안 보인다**(`D17`) — `reserved` 가 아니라 「예매 완료」.
 * 모르는 값은 그대로 두지 않고 「확인 중」으로 — 서버가 상태를 더했는데 화면이 모르면 영문 코드가 새어 나간다.
 */
const LABELS: Record<string, string> = {
  held: "좌석 선점 중",
  paying: "결제 처리 중",
  reserved: "예매 완료",
  cancelled: "취소됨",
  expired: "선점 시간 만료",
};

export function reservationStatusLabel(status: string): string {
  return LABELS[status] ?? "확인 중";
}
