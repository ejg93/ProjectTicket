/**
 * 모든 화면 테스트가 같이 쓰는 준비.
 *
 * `jest-dom` 의 단언을 들인다 — `toBeInTheDocument` 처럼 **깨진 이유가 문장으로 나오는** 단언이
 * 여기서 온다. 없으면 `expect(el).not.toBeNull()` 로 쓰게 되고, 실패했을 때 무엇이 없었는지가 안 드러난다.
 */
import "@testing-library/jest-dom/vitest";
