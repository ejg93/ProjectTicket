package com.projectticket.ticket

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * `quality-gates.md`(`D18`) 의 「CI 잡과 필수 검사」 표가 워크플로 실물과 같은가(청크 `P10`).
 *
 * **잡을 더하고 표를 안 고치면 여기서 선다.** 표는 「어느 잡이 머지를 막나」의 기록이라, 실물에 없는 잡이 표에 남거나
 * 표에 없는 잡이 워크플로에 생기면 다음 사람이 낡은 쪽을 믿는다 — CodeQL 이 두 번 빨간 채로 「아직 필수가 아니다」로
 * 적혀 있던 것이 그 모양이었다(`46a`·`46a-1`).
 *
 * **GitHub 쪽(가지 보호의 필수 목록)은 여기서 못 본다.** 그것은 사람이 `gh api` 로 맞추고 표의 「필수」 칸이 기록이다 —
 * 그 칸이 실물과 갈리는 것은 첫 PR 에서 드러난다(필수인데 안 도는 잡은 머지가 영영 안 된다).
 *
 * 빠른 레인이다 — 파일만 읽는다. `build.gradle.kts` 의 `inputs.files` 와 `verify-fingerprint.sh` 에 이 둘이 걸려 있어야
 * 문서만 고친 커밋에서도 돈다(점검 2차).
 */
class QualityGateDocTest {

    private val root: Path = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun the_job_table_matches_the_workflows() {
        val inDoc = Files.readString(root.resolve("doc/reference/quality-gates.md"))
            .lineSequence()
            .mapNotNull { ROW.find(it)?.let { m -> m.groupValues[1] to m.groupValues[2] } }
            .toSet()

        val inWorkflows = Files.list(root.resolve(".github/workflows")).use { files ->
            files.filter { WORKFLOW.matches(it.fileName.toString()) }
                .toList()
                .flatMap { file -> jobsOf(Files.readString(file)).map { file.fileName.toString() to it } }
        }.toSet()

        assertThat(inDoc)
            .describedAs("「CI 잡과 필수 검사」 표에서 행을 못 읽었다. 표의 꼴이 바뀌었으면 이 파서도 같이 고친다")
            .isNotEmpty()
        assertThat(inDoc)
            .describedAs("`quality-gates.md` 의 잡 표와 `.github/workflows/` 가 갈렸다 — 잡을 더하거나 뺐으면 표도 같이 고친다")
            .containsExactlyInAnyOrderElementsOf(inWorkflows)
    }

    /** `jobs:` 아래 두 칸 들여쓴 키. 워크플로마다 그 블록이 하나뿐이라 그 다음 최상위 키까지가 잡 목록이다 */
    private fun jobsOf(yaml: String): List<String> {
        val lines = yaml.lines()
        val start = lines.indexOfFirst { it.trimEnd() == "jobs:" }
        check(start >= 0) { "jobs: 블록이 없다" }
        return lines.drop(start + 1)
            .takeWhile { it.isBlank() || it.startsWith(" ") || it.startsWith("#") }
            .mapNotNull { JOB.find(it)?.groupValues?.get(1) }
    }

    private companion object {
        /**
         * 셋 다 **넓게 잡는다**(마무리 10차 독립 리뷰).
         *
         * 좁으면 문서 행과 워크플로 잡이 **같이** 빠져서 표와 실물이 갈려도 초록이다 — 대조가 조용히 0쌍을 비교한다.
         * 숫자를 못 읽던 앞 판이 그 모양이었다: `46b` 가 세울 `e2e` 잡이 양쪽에서 빠지고,
         * 문서를 바르게 고쳐도 한쪽만 읽혀 빨개졌다.
         */
        val WORKFLOW = Regex("""^[\w.-]+\.ya?ml$""")

        /** `| \`ci.yml\` | \`backend\` | 필수 | …` */
        val ROW = Regex("""^\| `([\w.-]+\.ya?ml)` \| `([\w-]+)` \| """)
        val JOB = Regex("""^  ([A-Za-z_][\w-]*):\s*$""")
    }
}
