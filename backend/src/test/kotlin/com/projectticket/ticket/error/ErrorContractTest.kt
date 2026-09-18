package com.projectticket.ticket.error

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 오류 `type` 목록이 문서와 같은가(`D5` 「`type` 목록 — 계약」).
 *
 * **`D5` 가 스스로 「이 표는 계약이다」라고 적었는데 그것을 재는 자리가 없었다**(점검 2차).
 * `D10` 의 지표 이름에는 `MetricNamesTest` 가 있는데 이쪽만 비어서, `account-not-found` 가
 * 계약에 없는 채로 5b 부터 나가고 있었다.
 *
 * 갈리면 무엇이 깨지나: 화면(39 이후)이 이 슬러그로 분기한다. 계약에 없는 슬러그는 화면이 모르는 값이라
 * **「알 수 없는 오류」로 떨어지고**, 계약에만 있는 슬러그는 **영영 안 오는 분기**가 된다. 둘 다 아무 오류도 안 낸다.
 *
 * 빠른 레인이다 — 파일과 열거형만 읽는다.
 */
class ErrorContractTest {

    private val root: Path = Path.of("..").toAbsolutePath().normalize()
    private val guidelines = Files.readString(root.resolve("doc/reference/api-guidelines.md"))

    @Test
    fun the_contract_and_the_enum_hold_the_same_slugs() {
        assertThat(contract().keys)
            .describedAs("`D5` 의 계약표와 `ErrorCode` 가 갈렸다. **고칠 것은 표다** — 슬러그가 늘면 계약 변경이다")
            .containsExactlyInAnyOrderElementsOf(ErrorCode.entries.map { it.slug })
    }

    @Test
    fun the_contract_and_the_enum_agree_on_the_status_code() {
        val mismatched = ErrorCode.entries
            .filter { contract()[it.slug] != it.status.value() }
            .map { "${it.slug}: 표=${contract()[it.slug]} 코드=${it.status.value()}" }

        // 상태 코드가 갈리면 화면이 슬러그를 보기도 전에 다르게 분기한다(4xx 와 5xx 는 처리가 다르다).
        assertThat(mismatched).describedAs("`D5` 의 상태 칸과 `ErrorCode` 가 갈렸다").isEmpty()
    }

    /**
     * 계약표를 `슬러그 → 상태` 로 읽는다.
     *
     * 한 행이 슬러그 여럿을 들기도 한다. 상태가 하나면 그 행 전부에 걸리고(`422`),
     * 슬러그 수와 같으면 차례로 짝짓는다(`400·405·415·404·500`). 그 밖은 표가 읽을 수 없는 모양이라 세운다.
     */
    private fun contract(): Map<String, Int> = buildMap {
        guidelines.lineSequence()
            .dropWhile { !it.startsWith("| 슬러그 |") }
            .drop(2)
            .takeWhile { it.startsWith("|") }
            .forEach { row ->
                val cells = row.split("|")
                val slugs = SLUG.findAll(cells[1]).map { it.groupValues[1] }.toList()
                val statuses = STATUS.findAll(cells[2]).map { it.value.toInt() }.toList()

                require(slugs.isNotEmpty() && statuses.isNotEmpty()) { "계약표의 행을 못 읽는다: $row" }
                require(statuses.size == 1 || statuses.size == slugs.size) {
                    "슬러그 ${slugs.size} 개에 상태 ${statuses.size} 개다. 하나를 같이 쓰거나 수를 맞춘다: $row"
                }

                slugs.forEachIndexed { i, slug -> put(slug, statuses.getOrElse(i) { statuses.first() }) }
            }
    }

    private companion object {
        /** 첫 칸의 백틱 이름. 굵게(`**`)가 붙은 행도 있어서 백틱만 본다 */
        val SLUG = Regex("`([a-z][a-z-]+)`")
        val STATUS = Regex("""\b[1-5]\d{2}\b""")
    }
}
