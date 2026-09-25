import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";

import ts from "typescript";
import { describe, expect, it } from "vitest";

/**
 * 화면이 가르는 오류 슬러그가 **계약표 안에 있는지** 본다(`api-guidelines.md` 「`type` 목록」, `G7c`).
 *
 * 백엔드가 슬러그를 바꾸면 **화면의 그 가지는 죽고 사용자는 영영 기본 문구만 본다** — 빌드도 타입 검사도 통과한다.
 * 계약표와 `ErrorCode` 가 같은지는 백엔드 `ErrorContractTest` 가 이미 잰다. 그래서 여기는 화면과 표만 대조하면 둘이 이어진다.
 *
 * **한쪽만 본다**(`D8` 「대개 한쪽만 본다」). 표에 있는데 화면이 안 가르는 슬러그는 기본 문구로 가는 것이 맞다.
 *
 * **두 꼴을 다 모은다** — `switch (…slug) { case "…": }` 와 `….slug === "…"`(`!==` 도). 목록 화면의 404 는
 * 후자로 가른다. `typescript` AST 로 걸어서 주석은 저절로 빠진다(`screen-text.test.ts` 와 같은 방법).
 *
 * **못 보는 것**: 변수에 담았다가 비교하는 슬러그. 지금 0건이다.
 */

/** vitest 는 `frontend/` 에서 돈다 */
const SRC = join(process.cwd(), "src");
const GUIDELINES = join(process.cwd(), "..", "doc", "reference", "api-guidelines.md");

type Branch = { where: string; slug: string };

function sourceFilesUnder(dir: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      found.push(...sourceFilesUnder(path));
    } else if (/\.tsx?$/.test(entry) && !/\.test\.tsx?$/.test(entry)) {
      found.push(path);
    }
  }
  return found;
}

function isSlugAccess(node: ts.Node): boolean {
  return ts.isPropertyAccessExpression(node) && node.name.text === "slug";
}

function branchesIn(file: string): Branch[] {
  const text = readFileSync(file, "utf8");
  const source = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const found: Branch[] = [];
  const take = (node: ts.Node, slug: string) => {
    const { line } = source.getLineAndCharacterOfPosition(node.getStart(source));
    found.push({ where: `${relative(SRC, file).replace(/\\/g, "/")}:${line + 1}`, slug });
  };

  const visit = (node: ts.Node) => {
    if (ts.isSwitchStatement(node) && isSlugAccess(node.expression)) {
      for (const clause of node.caseBlock.clauses) {
        if (ts.isCaseClause(clause) && ts.isStringLiteral(clause.expression)) {
          take(clause, clause.expression.text);
        }
      }
    } else if (
      ts.isBinaryExpression(node) &&
      [ts.SyntaxKind.EqualsEqualsEqualsToken, ts.SyntaxKind.ExclamationEqualsEqualsToken].includes(node.operatorToken.kind)
    ) {
      const [slugSide, other] = isSlugAccess(node.left) ? [node.left, node.right] : [node.right, node.left];
      if (isSlugAccess(slugSide) && ts.isStringLiteral(other)) {
        take(node, other.text);
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
  return found;
}

/** 「`type` 목록」 표 첫 칸의 백틱 슬러그들. 한 행이 여럿을 들기도 한다 — 백엔드 `ErrorContractTest` 와 같은 읽기다 */
function contractSlugs(): Set<string> {
  const lines = readFileSync(GUIDELINES, "utf8").split(/\r?\n/);
  const start = lines.findIndex((line) => line.startsWith("| 슬러그 |"));
  const rows: string[] = [];
  for (let i = start + 2; start >= 0 && i < lines.length && lines[i].startsWith("|"); i++) {
    rows.push(lines[i]);
  }
  return new Set(rows.flatMap((row) => [...row.split("|")[1].matchAll(/`([a-z][a-z-]+)`/g)].map((m) => m[1])));
}

describe("화면의 오류 분기는 계약표 안에 있다", () => {
  const branches = sourceFilesUnder(SRC).flatMap(branchesIn);
  const contract = contractSlugs();

  it("읽을 것이 있다", () => {
    // 표 꼴이 바뀌거나 분기 꼴이 바뀌면 0개를 읽고 조용히 통과한다 — 「못 읽었다」와 「갈렸다」를 가른다(`D18`).
    expect(contract.size, "api-guidelines.md 「| 슬러그 |」 표를 못 읽었다").toBeGreaterThan(0);
    expect(branches.length, "화면에서 슬러그 분기를 하나도 못 읽었다 — 분기 꼴이 바뀌었으면 이 파서도 같이 고친다").toBeGreaterThan(0);
  });

  it("화면이 가르는 슬러그가 전부 표에 있다", () => {
    const unknown = branches.filter((b) => !contract.has(b.slug)).map((b) => `${b.where} "${b.slug}"`);
    expect(unknown, "계약표에 없는 슬러그를 가른다 — 그 가지는 영영 안 탄다(api-guidelines.md 「`type` 목록」)").toEqual([]);
  });
});
