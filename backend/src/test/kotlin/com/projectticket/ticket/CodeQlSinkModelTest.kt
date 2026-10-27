package com.projectticket.ticket

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * CodeQL 싱크 모델(`.github/codeql/extensions/projectticket-java/models/`)이 가리키는 메서드가 클래스패스에 실제로 있나(`G23`).
 *
 * **모델이 틀리면 CodeQL 은 조용히 초록이다.** 패키지·타입·시그니처 중 하나만 어긋나도 싱크가 0 개가 되고,
 * `java/sql-injection` 은 이 저장소의 SQL 을 하나도 안 본 채 「경보 0」을 낸다 — 0 이 「없다」가 아니라 「안 봤다」가 된다.
 * Spring 이 판을 올리며 `JdbcClient` 를 옮기거나 `sql` 의 인자를 바꾸면 그렇게 된다. CodeQL 은 CI 에서만 돌아 로컬에서 못 잰다.
 *
 * 여기서는 모델의 「패키지 · 타입 · 메서드 · 시그니처」 칸을 글자로 읽어 리플렉션으로 찾는다. **경보가 실제로 서는지**는
 * 탐침 가지가 잰다(`quality-gates.md` 「부순 날」).
 *
 * 빠른 레인이다 — 파일과 클래스만 읽는다. `build.gradle.kts` 의 `inputs.files` 와 `verify-fingerprint.sh` 에 모델 파일이 있어야
 * 그 파일만 고친 커밋에서도 돈다(점검 2차).
 */
class CodeQlSinkModelTest {

    private val models: Path = Path.of("..").toAbsolutePath().normalize().resolve(".github/codeql/extensions/projectticket-java/models")

    @Test
    fun every_sink_in_the_models_exists_on_the_classpath() {
        val rows = Files.list(models).use { files ->
            files.filter { it.fileName.toString().endsWith(".model.yml") }.toList()
                .flatMap { file -> Files.readAllLines(file).mapNotNull { ROW.find(it) }.map { file.fileName.toString() to it } }
        }

        assertThat(rows)
            .describedAs("싱크 모델에서 데이터 행을 못 읽었다(`%s`). 행의 꼴이 바뀌었으면 이 파서도 같이 고친다", models)
            .isNotEmpty()

        rows.forEach { (file, row) ->
            val (pkg, type, method, signature) = row.destructured
            val owner = runCatching { Class.forName("$pkg.$type") }.getOrNull()
            assertThat(owner).describedAs("%s — `%s.%s` 가 클래스패스에 없다. 판이 올라 옮겨졌으면 모델을 고친다", file, pkg, type).isNotNull()

            val parameters = signature.removeSurrounding("(", ")").split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::classOf)
            val found = runCatching { checkNotNull(owner).getMethod(method, *parameters.toTypedArray()) }.getOrNull()
            assertThat(found).describedAs("%s — `%s.%s%s` 가 없다. 인자가 바뀌었으면 시그니처 칸을 고친다", file, type, method, signature).isNotNull()
        }
    }

    /** 시그니처 칸은 `java.lang` 의 짧은 이름이나 완전한 이름이다(CodeQL 모델 규칙) */
    private fun classOf(name: String): Class<*> = Class.forName(if ('.' in name) name else "java.lang.$name")

    private companion object {
        /** `- ["패키지", "타입", 하위타입, "메서드", "(시그니처)", …]` — 앞 다섯 칸만 본다 */
        val ROW = Regex("""^\s*-\s*\["([\w.]+)",\s*"(\w+)",\s*(?:true|false),\s*"(\w+)",\s*"(\([^"]*\))"""")
    }
}
