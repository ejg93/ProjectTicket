package com.projectticket.ticket.outbox

import com.projectticket.ticket.MigrationSql
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 코드가 내는 사건 이름이 `D11` 카탈로그와 같은가. 빠른 레인 — 파일만 읽는다.
 *
 * **문서가 계약이다**(`D11`). 이름을 바꾸는 것은 새 사건이라, 코드만 고치면 소비자가 안 듣는 사건이 조용히 나간다.
 * 마이그레이션의 `outbox_type_check` 까지 셋이 같은 목록을 든다(`D14` 「열거값을 어디에 두나」).
 */
class EventCatalogTest {

    private val root: Path = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun enum_matches_the_catalog_table() {
        val doc = Files.readString(root.resolve("doc/reference/event-catalog.md"))
        // 표제가 없으면 `substringAfter` 가 문서 전문을 돌려준다 — 다른 표를 카탈로그로 읽는다(`I10-1`)
        assertThat(doc).describedAs("event-catalog.md 에서 카탈로그 표제를 못 찾았다 — 「## 카탈로그」").contains("## 카탈로그")
        // 「아직 안 내는 것」 표가 바로 아래에 있다 — 거기서 끊는다. 헤더 행(`type`)도 같은 모양이라 뺀다.
        val catalog = doc
            .substringAfter("## 카탈로그")
            .substringBefore("**아직 안 내는 것**")
            .lineSequence()
            .filter { it.startsWith("| `") }
            .map { it.split("|")[1].trim().trim('`') }
            .filterNot { it == "type" }
            .toSet()

        assertThat(EventType.entries.map { it.code }.toSet())
            .describedAs("코드가 내는 사건 ⊆ D11 카탈로그. 새 사건은 문서에 「누가 받나」를 먼저 채운다")
            .isEqualTo(catalog)
    }

    @Test
    fun migration_check_matches_the_enum() {
        // 마지막 판을 읽는다 — 파일 하나(`V13`)를 박으면 뒤 판이 제약을 다시 걸었을 때 낡은 목록을 본다(`I10-1`)
        val values = MigrationSql.closedValueSets(root)["outbox_type_check"]
        assertThat(values).describedAs("마이그레이션에서 `outbox_type_check` 를 못 읽었다").isNotNull().isNotEmpty()

        assertThat(checkNotNull(values) { "바로 위 단언이 null 을 막는다" }.toSet()).isEqualTo(EventType.entries.map { it.code }.toSet())
    }
}
