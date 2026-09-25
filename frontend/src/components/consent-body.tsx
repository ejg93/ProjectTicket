/**
 * 약관 본문(`consent_item.body`)을 그린다(`39-1`, 약관규제법 제3조 — 중요한 내용은 눈에 띄게).
 *
 * **라이브러리를 안 들인다.** 본문은 우리 시드(`V3`)라 쓰는 표기가 셋뿐이다 — `## ` 머리, `**…**` 굵게, 빈 줄 문단.
 * 그 셋만 그리고 **나머지는 글자 그대로** 둔다. `dangerouslySetInnerHTML` 을 안 쓴다(`react/no-danger`) — React 가 글자를 이스케이프한다.
 * 표기가 늘면(목록·링크) 그때 이 파일을 넓히거나 라이브러리를 근거와 함께 들인다.
 */

/** `GET /api/consent-items/{code}` 의 응답. 서버 JSON 이 snake_case 라 **받은 그대로 적는다**(`D5`) */
export type ConsentItemDetail = {
  code: string;
  title: string;
  version: number;
  effective_at: string;
  body: string | null;
  purpose: string | null;
  collected_items: string | null;
  retention_period: string | null;
  refusal_disadvantage: string | null;
};

type Block = { kind: "heading"; text: string } | { kind: "paragraph"; text: string };

/** 줄 단위로 읽는다 — `## ` 는 머리, 빈 줄은 문단 끝, 나머지 줄은 이어서 한 문단(마크다운의 부드러운 줄바꿈) */
export function blocksOf(body: string): Block[] {
  const blocks: Block[] = [];
  let lines: string[] = [];
  const flush = () => {
    if (lines.length > 0) {
      blocks.push({ kind: "paragraph", text: lines.join(" ") });
      lines = [];
    }
  };
  for (const raw of body.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.startsWith("## ")) {
      flush();
      blocks.push({ kind: "heading", text: line.slice(3) });
    } else if (line === "") {
      flush();
    } else {
      lines.push(line);
    }
  }
  flush();
  return blocks;
}

/** `**…**` 만 굵게. 짝이 안 맞는 `**` 는 글자 그대로 남는다 */
function Inline({ text }: { text: string }) {
  const parts = text.split(/\*\*(.+?)\*\*/);
  // split 이 잡은 괄호 조각은 홀수 번째에 온다
  return <>{parts.map((part, i) => (i % 2 === 1 ? <strong key={i}>{part}</strong> : part))}</>;
}

export function ConsentBody({ body }: { body: string }) {
  return (
    <>
      {blocksOf(body).map((block, i) =>
        block.kind === "heading" ? (
          <h2 key={i}>{block.text}</h2>
        ) : (
          <p key={i}>
            <Inline text={block.text} />
          </p>
        ),
      )}
    </>
  );
}
