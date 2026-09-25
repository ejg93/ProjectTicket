/** 기획사 화면이 받는 모양(`45a`·`45a-1`). 서버 JSON 이 snake_case 라 **받은 그대로 적는다**(`D5`) */

export type OrganizerEvent = {
  event_id: number;
  title: string;
  organizer_id: number;
  organizer_name: string;
  created_at: string;
  performance_count: number;
};

export type OrganizerPerformance = {
  performance_id: number;
  status: string;
  starts_at: string;
  sales_open_at: string;
  sales_close_at: string;
  hall_name: string;
  venue_name: string;
  seat_count: number;
  sold_count: number;
};

export type OrganizerEventDetail = {
  event_id: number;
  title: string;
  organizer_id: number;
  organizer_name: string;
  grades: { code: string; name: string; price: number }[];
  performances: OrganizerPerformance[];
};

export type Organizer = { organizer_id: number; name: string };

export type Hall = {
  hall_id: number;
  name: string;
  venue_name: string;
  sections: { code: string; seat_count: number }[];
};

/** 회차 상태의 화면 이름. 내부 값을 그대로 안 보인다(`D17`) */
export function performanceStatusLabel(status: string): string {
  return { draft: "오픈 전", open: "판매 중", closed: "판매 마감", cancelled: "취소됨" }[status] ?? "확인 중";
}

/** `datetime-local` 의 값(`2026-10-01T19:30`)을 KST 오프셋이 붙은 시각으로 — 서버는 오프셋 없는 시각을 400 으로 받는다(`D7`) */
export function kstIso(local: string): string {
  return `${local}:00+09:00`;
}
