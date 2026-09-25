import type { Metadata } from "next";
import Link from "next/link";

import { apiSession } from "@/lib/api-session";

import { isNoOrganizerRole, NoOrganizerRole } from "../../no-role";
import type { Hall, Organizer } from "../../types";

import { EventForm } from "./event-form";

export const metadata: Metadata = {
  title: "공연 등록 · ProjectTicket",
};

/** 공연 등록(`45b`). 고를 목록 둘(내 기획사·홀의 구역)을 서버가 먼저 읽는다(`45a-1`) */
export default async function NewEventPage() {
  let organizers: Organizer[];
  let halls: Hall[];
  try {
    [organizers, halls] = await Promise.all([
      apiSession<Organizer[]>("/api/organizer/organizers"),
      apiSession<Hall[]>("/api/organizer/halls"),
    ]);
  } catch (thrown) {
    if (isNoOrganizerRole(thrown)) return <NoOrganizerRole />;
    throw thrown;
  }
  // 구역 코드는 홀마다 겹친다(F1-A). 등급은 공연에 걸리고 홀은 회차가 고르니 코드만 모은다.
  const sections = [...new Set(halls.flatMap((h) => h.sections.map((s) => s.code)))].sort();

  return (
    <div>
      <h1>공연 등록</h1>
      {organizers.length === 0 ? (
        <p>소속된 기획사가 없습니다. 관리자에게 기획사 소속을 요청해 주세요.</p>
      ) : (
        <EventForm organizers={organizers} sections={sections} />
      )}
      <p className="muted">
        <Link href="/organizer">내 공연 목록</Link>
      </p>
    </div>
  );
}
