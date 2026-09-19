import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { EventList, type EventPage } from "./event-list";

/**
 * 목록이 **무엇을 고르나**(`D8` — 고정할 것은 판단이지 그림이 아니다).
 *
 * 여기서 막는 넷: 빈 상태를 말하는가, `min_price` 가 `null` 일 때 0원으로 안 그리는가,
 * 시각을 KST 로 그리는가(`D7`), 쪽이 하나일 때 이동을 안 그리는가.
 */

function page(overrides: Partial<EventPage> = {}): EventPage {
  return {
    items: [
      {
        event_id: 1,
        title: "가을 콘서트",
        organizer_name: "테스트기획",
        // UTC 의 Z 로 온다. 화면은 KST 로 그린다 — 19:00 이어야 한다.
        starts_at: "2026-09-25T10:00:00Z",
        performance_count: 2,
        min_price: 121000,
      },
    ],
    page: 0,
    size: 20,
    total: 1,
    ...overrides,
  };
}

describe("공연 목록", () => {
  it("비어 있으면 비었다고 말한다", () => {
    render(<EventList events={page({ items: [], total: 0 })} />);

    // 아무것도 안 그리면 고장과 구분이 안 된다(`D17` 「빈 상태」).
    expect(screen.getByText(/판매 중인 공연이 없습니다/)).toBeInTheDocument();
  });

  it("첫 회차를 KST 로 그린다", () => {
    render(<EventList events={page()} />);

    expect(screen.getByText(/2026-09-25 19:00/)).toBeInTheDocument();
  });

  it("최저가가 없으면 가격 줄을 안 그린다", () => {
    render(<EventList events={page({ items: [{ ...page().items[0], min_price: null }] })} />);

    // 0원으로 그리면 공짜 공연과 안 갈린다.
    expect(screen.queryByText(/원부터/)).not.toBeInTheDocument();
    expect(screen.queryByText(/0원/)).not.toBeInTheDocument();
  });

  it("최저가가 있으면 얼마부터인지 적는다", () => {
    render(<EventList events={page()} />);

    expect(screen.getByText(/121,000원부터/)).toBeInTheDocument();
  });

  it("쪽이 하나면 이동을 안 그린다", () => {
    render(<EventList events={page()} />);

    expect(screen.queryByRole("navigation", { name: "쪽 이동" })).not.toBeInTheDocument();
  });

  it("다음 쪽이 있으면 링크를 준다", () => {
    render(<EventList events={page({ total: 45 })} />);

    expect(screen.getByRole("link", { name: "다음" })).toHaveAttribute("href", "/events?page=1");
    // 첫 쪽에서는 이전이 없다. 눌러 봐야 같은 자리다.
    expect(screen.queryByRole("link", { name: "이전" })).not.toBeInTheDocument();
  });
});
