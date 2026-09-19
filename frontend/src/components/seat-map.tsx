"use client";

import { useState } from "react";

import { money } from "@/lib/format";

/**
 * 좌석 현황 응답(`D20`). **키가 한 글자인 것까지 그대로 받는다** — 서버가 대역폭 때문에 줄인 것이고
 * 화면이 이름을 붙여 다시 쓰면 같은 값에 이름이 둘 생긴다(`D5`).
 */
export type SeatMapData = {
  version: number;
  grades: { code: string; name: string; price: number }[];
  sections: {
    code: string;
    name: string;
    grade: string;
    rows: { label: string; seats: { id: number; n: number; s: string }[] }[];
  }[];
};

/** 한 번에 고르는 좌석은 **4석**이다(ADR 0003). 서버도 같은 수를 들고 넘으면 `over-limit` 이다 */
export const MAX_SEATS = 4;

/**
 * 상태 한 글자 → 사람이 읽는 말.
 *
 * **색만으로 알리지 않는다**(KWCAG 2.2 / WCAG 1.4.1). 각 좌석의 이름에 상태가 글자로 들어가고,
 * 색은 같은 사실을 한 번 더 말하는 자리다 — 색을 못 보는 사람이 정보를 잃지 않는다.
 */
const STATUS_TEXT: Record<string, string> = {
  A: "선택 가능",
  H: "선점됨",
  R: "판매됨",
};

/**
 * 좌석도.
 *
 * **SVG 가 아니라 체크박스다.** 분할표는 SVG·캔버스로 적었는데, 그리는 것보다 **고르는 것**이 이 화면의 일이고
 * 접근성은 여기서 관례가 아니라 법 요건이다(`screen-rules.md` 「기준」 — 장애인차별금지법 제20조·제21조).
 * 체크박스는 키보드 이동·선택 상태·낭독기 이름이 브라우저에서 공짜로 오고, SVG 로 그리면 그 넷을 손으로 다시 만든다.
 * 좌석 수가 만 단위로 커져서 DOM 이 버거워지면 그때 캔버스를 얹고 이 판단을 다시 적는다.
 *
 * **상한을 여기서 든다**(4석, ADR 0003). 서버가 같은 수를 다시 세지만(`over-limit`), 누른 자리에서 막지 않으면
 * 사용자는 다섯째 좌석을 고르고 나서야 안 된다는 것을 안다.
 */
export function SeatMap({
  data,
  onChange,
}: {
  data: SeatMapData;
  onChange?: (selected: number[]) => void;
}) {
  const [selected, setSelected] = useState<number[]>([]);
  const [notice, setNotice] = useState<string | null>(null);

  const priceOf = (gradeCode: string) => data.grades.find((g) => g.code === gradeCode)?.price ?? null;

  function toggle(seatId: number) {
    const next = selected.includes(seatId)
      ? selected.filter((id) => id !== seatId)
      : [...selected, seatId];

    if (next.length > MAX_SEATS) {
      // 고르려던 것을 안 고른 채로 둔다. 먼저 고른 것을 밀어내면 **사용자가 안 누른 자리가 풀린다**.
      setNotice(`한 번에 ${MAX_SEATS}석까지 고르실 수 있습니다. 먼저 고른 좌석을 해제해 주세요.`);
      return;
    }

    setNotice(null);
    setSelected(next);
    onChange?.(next);
  }

  return (
    <div>
      <ul className="legend">
        {data.grades.map((grade) => (
          <li key={grade.code}>
            {grade.name} {money(grade.price)}
          </li>
        ))}
      </ul>

      {data.sections.map((section) => (
        <section key={section.code} aria-labelledby={`section-${section.code}`}>
          <h3 id={`section-${section.code}`}>{section.name}</h3>
          {section.rows.map((row) => (
            <div key={row.label} className="seat-row">
              <span aria-hidden="true">{row.label}</span>
              {row.seats.map((seat) => {
                const price = priceOf(section.grade);
                const status = STATUS_TEXT[seat.s] ?? seat.s;
                return (
                  <label key={seat.id} className={`seat seat-${seat.s}`}>
                    <input
                      type="checkbox"
                      checked={selected.includes(seat.id)}
                      disabled={seat.s !== "A"}
                      onChange={() => toggle(seat.id)}
                      /*
                        이름에 **어디의 몇 번인지와 상태**를 다 넣는다. 숫자만 읽어 주면 낭독기 사용자는
                        구역과 열을 앞뒤 문맥에서 기억해야 한다.
                      */
                      aria-label={`${section.name} ${row.label}열 ${seat.n}번, ${status}${
                        price === null ? "" : `, ${money(price)}`
                      }`}
                    />
                    <span aria-hidden="true">{seat.n}</span>
                  </label>
                );
              })}
            </div>
          ))}
        </section>
      ))}

      {/* 막힌 이유를 말한다(`D17`). 조용히 안 골라지면 사용자는 화면이 고장난 줄 안다 */}
      <p role="status">
        {notice ?? `${selected.length}석 선택 (최대 ${MAX_SEATS}석)`}
      </p>
    </div>
  );
}
