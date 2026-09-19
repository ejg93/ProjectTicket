/**
 * 화면에 값을 그리는 자리(`D7`·`D5` 「값의 형식」).
 *
 * **시각은 KST 로 그린다.** 서버는 UTC 의 `Z` 로 주고 바꾸지 않는다 — 바꾸는 것은 화면 몫이다.
 * 브라우저의 시간대를 쓰지 않는다: 이 서비스의 「관람일」은 KST 달력일이라(`D7`), 다른 나라에서 열면
 * **화면의 날짜와 환불 계산의 날짜가 갈린다.**
 *
 * **시각은 직접 조립한다** — `Intl` 의 날짜 글자는 판마다 조금씩 달라서 테스트가 흔들린다.
 * **금액은 `toLocaleString` 을 쓴다**: 세 자리 쉼표는 그 흔들림이 없고, 손으로 짜면 자릿수 규칙을 또 만드는 것이다.
 */

const KST_OFFSET_MINUTES = 9 * 60;

function kstParts(iso: string) {
  const at = new Date(iso);
  const kst = new Date(at.getTime() + KST_OFFSET_MINUTES * 60_000);
  return {
    year: kst.getUTCFullYear(),
    month: kst.getUTCMonth() + 1,
    day: kst.getUTCDate(),
    hour: kst.getUTCHours(),
    minute: kst.getUTCMinutes(),
  };
}

const pad = (value: number) => String(value).padStart(2, "0");

/** `2026-09-25T10:00:00Z` → `2026-09-25 19:00` (KST) */
export function dateTime(iso: string): string {
  const { year, month, day, hour, minute } = kstParts(iso);
  return `${year}-${pad(month)}-${pad(day)} ${pad(hour)}:${pad(minute)}`;
}

/** `2026-09-25T10:00:00Z` → `2026-09-25` (KST 달력일 — 환불 계산이 세는 것과 같은 날이다) */
export function date(iso: string): string {
  const { year, month, day } = kstParts(iso);
  return `${year}-${pad(month)}-${pad(day)}`;
}

/** `154000` → `154,000원`. 금액은 정수 원이다(`D5`) */
export function money(won: number): string {
  return `${won.toLocaleString("ko-KR")}원`;
}
