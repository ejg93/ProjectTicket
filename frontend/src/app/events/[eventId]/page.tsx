import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";

import { ApiError, apiPublic } from "@/lib/api";
import { dateTime, money } from "@/lib/format";

/** 서버 JSON 이 snake_case 라 **받은 그대로 적는다**(`D5`) */
type EventDetail = {
  event_id: number;
  title: string;
  organizer_name: string;
  grades: { code: string; name: string; price: number }[];
  performances: {
    performance_id: number;
    starts_at: string;
    sales_open_at: string;
    sales_close_at: string;
    hall_name: string;
    venue_name: string;
  }[];
};

/**
 * 제목과 본문이 같이 쓴다. **두 번 부르는 것이 아니다** — App Router 가 한 번의 렌더 안에서 같은 `fetch` 를
 * 합친다(요청 메모이제이션). 합쳐지지 않는 판이 오면 백엔드 호출 수가 화면 수의 두 배로 뛰므로 그때 알아챈다.
 */
async function loadEvent(eventId: string): Promise<EventDetail> {
  try {
    return await apiPublic<EventDetail>(`/api/events/${eventId}`);
  } catch (thrown) {
    // 없는 공연은 Next 의 404 화면으로 보낸다. `event-not-found` 만 그렇게 본다 —
    // 500 을 404 로 바꾸면 고장이 「없는 주소」로 보이고 아무도 원인을 안 찾는다.
    if (thrown instanceof ApiError && thrown.slug === "event-not-found") {
      notFound();
    }
    throw thrown;
  }
}

export async function generateMetadata({
  params,
}: {
  params: Promise<{ eventId: string }>;
}): Promise<Metadata> {
  const { eventId } = await params;
  return { title: `${(await loadEvent(eventId)).title} · ProjectTicket` };
}

/**
 * 공연 상세. 회차를 고르는 자리다.
 *
 * **회차가 없는 것과 공연이 없는 것을 가른다**(`D17` 「빈 상태」). 서버가 그 둘을 404 와 빈 목록으로
 * 갈라 주므로(`40a`) 화면도 갈라서 그린다.
 *
 * 좌석 고르기는 `41` 이 붙인다. 지금은 회차까지다.
 */
export default async function EventDetailPage({
  params,
}: {
  params: Promise<{ eventId: string }>;
}) {
  const { eventId } = await params;
  const event = await loadEvent(eventId);

  return (
    <div>
      <h1>{event.title}</h1>
      <p className="muted">{event.organizer_name}</p>

      <h2>등급</h2>
      {event.grades.length === 0 ? (
        <p>등급이 아직 정해지지 않았습니다.</p>
      ) : (
        <ul>
          {event.grades.map((grade) => (
            <li key={grade.code}>
              {grade.name} {money(grade.price)}
            </li>
          ))}
        </ul>
      )}

      <h2>회차</h2>
      {event.performances.length === 0 ? (
        <p>판매 중인 회차가 없습니다. 예매가 열리면 여기에 표시됩니다.</p>
      ) : (
        <ul>
          {event.performances.map((performance) => (
            <li key={performance.performance_id}>
              <Link href={`/performances/${performance.performance_id}`}>
                {dateTime(performance.starts_at)}
              </Link>{" "}
              · {performance.venue_name} {performance.hall_name}
            </li>
          ))}
        </ul>
      )}

      <p className="muted">
        <Link href="/events">공연 목록</Link>
      </p>
    </div>
  );
}
