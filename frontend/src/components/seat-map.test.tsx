import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { MAX_SEATS, SeatMap, type SeatMapData } from "./seat-map";

/**
 * 좌석도가 **무엇을 막고 무엇을 말하나**(`D8`·`D17`·접근성).
 *
 * 여기서 막는 넷: 이름에 상태가 들어가는가(색만으로 알리지 않는다), 잡힌 좌석을 못 고르는가,
 * 상한을 넘을 때 **먼저 고른 것을 안 밀어내는가**, 그리고 axe 위반이 없는가.
 */

function seatMap(): SeatMapData {
  return {
    version: 7,
    grades: [
      { code: "VIP", name: "VIP", price: 154000 },
      { code: "R", name: "R", price: 121000 },
    ],
    sections: [
      {
        code: "F1-A",
        name: "1층 A구역",
        grade: "VIP",
        rows: [
          {
            label: "A",
            seats: [
              { id: 1, n: 1, s: "A" },
              { id: 2, n: 2, s: "H" },
              { id: 3, n: 3, s: "R" },
              { id: 4, n: 4, s: "A" },
              { id: 5, n: 5, s: "A" },
              { id: 6, n: 6, s: "A" },
              { id: 7, n: 7, s: "A" },
            ],
          },
        ],
      },
    ],
  };
}

describe("좌석도", () => {
  it("좌석 이름에 자리와 상태와 가격이 들어간다", () => {
    render(<SeatMap data={seatMap()} />);

    // 숫자만 읽어 주면 낭독기 사용자는 구역과 열을 문맥에서 기억해야 한다.
    expect(
      screen.getByRole("checkbox", { name: "1층 A구역 A열 1번, 선택 가능, 154,000원" }),
    ).toBeInTheDocument();
  });

  it("선점·판매된 좌석은 못 고른다", () => {
    render(<SeatMap data={seatMap()} />);

    expect(screen.getByRole("checkbox", { name: /2번, 선점됨/ })).toBeDisabled();
    expect(screen.getByRole("checkbox", { name: /3번, 판매됨/ })).toBeDisabled();
  });

  it("고른 수를 말한다", async () => {
    render(<SeatMap data={seatMap()} />);

    await userEvent.click(screen.getByRole("checkbox", { name: /1번, 선택 가능/ }));

    expect(screen.getByRole("status")).toHaveTextContent(`1석 선택 (최대 ${MAX_SEATS}석)`);
  });

  it("상한을 넘기면 먼저 고른 것을 안 밀어낸다", async () => {
    render(<SeatMap data={seatMap()} />);
    const available = [1, 4, 5, 6, 7];

    for (const number of available) {
      await userEvent.click(screen.getByRole("checkbox", { name: new RegExp(`${number}번, 선택 가능`) }));
    }

    // 다섯째는 안 골라지고, 먼저 고른 넷은 그대로다. 밀어내면 사용자가 안 누른 자리가 풀린다.
    expect(screen.getByRole("checkbox", { name: /7번, 선택 가능/ })).not.toBeChecked();
    expect(screen.getByRole("checkbox", { name: /1번, 선택 가능/ })).toBeChecked();
    expect(screen.getByRole("status")).toHaveTextContent(`${MAX_SEATS}석까지`);
  });

  it("고른 좌석을 부르는 쪽에 알린다", async () => {
    const seen: number[][] = [];
    render(<SeatMap data={seatMap()} onChange={(ids) => seen.push(ids)} />);

    await userEvent.click(screen.getByRole("checkbox", { name: /4번, 선택 가능/ }));

    // 예매 흐름(`42`)이 이 값으로 선점을 건다. 좌석 번호가 아니라 `performance_seat_id` 다.
    expect(seen.at(-1)).toEqual([4]);
  });

  it("접근성 위반이 없다", async () => {
    const { container } = render(<SeatMap data={seatMap()} />);

    await expectNoAxeViolations(container);
  });
});
