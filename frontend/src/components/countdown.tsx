"use client";

import { useEffect, useState } from "react";

/**
 * 남은 시간(`D17` 「기다리는 동안」).
 *
 * **서버가 준 마감 시각을 센다.** 남은 초를 받아서 세면 브라우저 시계가 느린 사람에게 이미 끝난 선점이
 * 계속 남아 있는 것처럼 보인다 — 마감은 순간이고 그 순간은 서버가 정한다(`D7`).
 *
 * **여기서 서버를 안 부른다.** 0 이 됐다고 화면이 선점을 풀지 않는다 — 푸는 것은 만료 배치(`22`)고,
 * 이 숫자는 **사용자가 서두를 이유**를 보여줄 뿐이다. 0 뒤의 요청은 서버가 `hold-expired` 로 막는다.
 *
 * 첫 그림은 서버에서도 그려지므로 `mounted` 전에는 남은 시간을 안 그린다 — 서버와 클라이언트의 시계가
 * 달라서 hydration 이 어긋난다.
 */
export function Countdown({ deadline, label }: { deadline: string; label: string }) {
  const [remaining, setRemaining] = useState<number | null>(null);

  useEffect(() => {
    const tick = () => setRemaining(Math.max(0, new Date(deadline).getTime() - Date.now()));
    tick();
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [deadline]);

  if (remaining === null) {
    return <p role="timer">{label}</p>;
  }

  if (remaining === 0) {
    return (
      <p role="alert">시간이 지나 좌석이 풀렸습니다. 좌석을 다시 골라 주세요.</p>
    );
  }

  const seconds = Math.floor(remaining / 1000);
  const mmss = `${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;

  return (
    <p role="timer">
      {label} {mmss}
    </p>
  );
}
