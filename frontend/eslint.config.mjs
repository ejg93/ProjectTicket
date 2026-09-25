import js from "@eslint/js";
import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import jsxA11y from "eslint-plugin-jsx-a11y";

const eslintConfig = defineConfig([
  // **기본 묶음을 깐다.** `eslint-config-next` 는 이것을 안 포함해서 `no-empty` 같은 것이 꺼져 있다.
  // **버전을 eslint 와 맞춰 박는다** — `@eslint/js@latest` 는 eslint 10 을 peer 로 부르는데 여기는 9 다.
  js.configs.recommended,
  ...nextVitals,
  ...nextTs,

  // 접근성 규칙 전체. `eslint-config-next` 도 jsx-a11y 를 일부 켜지만 그것은 부분집합이라
  // 라벨 없는 입력칸이 그물을 빠져나간다. **화면을 만들기 전에 켠다** — 다 만든 뒤에 켜면
  // 이미 나온 마크업을 되돌리는 일이 된다.
  //
  // 이 도구는 글자만 보므로 빠뜨린 것만 잡는다. 대비비와 탭 순서는 못 본다 — 그쪽은 `D17` 몫이다.
  //
  // 규칙만 가져온다. 설정 통째로 넣으면 "Cannot redefine plugin" 으로 죽는다 —
  // `eslint-config-next` 가 이미 같은 이름으로 등록해 뒀다.
  { rules: jsxA11y.flatConfigs.recommended.rules },

  // 남이 준 글자를 HTML 로 그리는 자리를 막는다(`D9`, OWASP A03). React 는 기본으로 글자를
  // 이스케이프하는데 `dangerouslySetInnerHTML` 은 그것을 끈다.
  { rules: { "react/no-danger": "error" } },

  // **서버를 부르는 입구를 파일 둘에 가둔다**(`D16` 「서버를 부르는 입구」). 문서에만 있던 규칙을
  // 여기서 강제 지점으로 내린다 — `fetch` 를 화면에서 직접 쓰면 CSRF·오류 변환을 안 거친 응답이
  // 화면에 닿고, `next/headers` 를 다른 파일이 들면 세션 운반이 두 군데가 된다.
  //
  // **클라이언트 컴포넌트가 `api-session` 을 드는 것은 여기서 안 막는다** — `next/headers` 가
  // 클라이언트 번들에 들어가면 `next build` 가 자체로 선다. 빌드가 막는 것을 린트가 또 막으면 규칙이 두 벌이다.
  {
    rules: {
      "no-restricted-globals": [
        "error",
        {
          name: "fetch",
          message: "서버는 api()·apiPublic()·apiSession() 으로만 부른다(frontend-rules.md)",
        },
      ],
      "no-restricted-imports": [
        "error",
        {
          paths: [
            {
              name: "next/headers",
              message: "세션 운반은 src/lib/api-session.ts 한 곳이다(frontend-rules.md)",
            },
          ],
        },
      ],
    },
  },
  // 화면 코드는 `.message` 를 안 읽는다(`frontend-rules.md` 「원인을 화면에 안 적는다」, `G7c`). 서버 문구나 스택이 들어올 수 있다.
  // **변수 이름으로 안 가린다** — 이 저장소의 `catch` 변수는 `thrown` 이라 `error`·`err`·`e` 로 가리면 하나도 못 문다.
  {
    files: ["src/app/**/*.{ts,tsx}", "src/components/**/*.{ts,tsx}"],
    ignores: ["**/*.test.ts", "**/*.test.tsx"],
    rules: {
      "no-restricted-syntax": [
        "error",
        {
          selector: "MemberExpression[property.name='message'], MemberExpression[property.value='message']",
          message: "화면은 원인 문구를 안 적는다 — 슬러그로 가르고 문구는 화면이 정한다. 되짚을 값은 digest 다(frontend-rules.md)",
        },
      ],
    },
  },
  {
    files: ["src/lib/api.ts", "src/lib/api-session.ts"],
    rules: { "no-restricted-globals": "off", "no-restricted-imports": "off" },
  },
  {
    // 테스트는 `fetch` 를 흉내 내는 자리라 부르는 것이 일이다.
    files: ["**/*.test.ts", "**/*.test.tsx", "vitest.setup.ts"],
    rules: { "no-restricted-globals": "off" },
  },

  globalIgnores([".next/**", "out/**", "build/**", "next-env.d.ts"]),
]);

export default eslintConfig;
