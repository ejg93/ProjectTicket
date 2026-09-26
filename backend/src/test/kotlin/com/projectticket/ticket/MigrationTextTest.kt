package com.projectticket.ticket

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `coding-rules.md` 「마이그레이션」의 글자로 잡히는 조항 둘을 마이그레이션 파일에 건다(`G7b`, `G5` 원장 ⑤⑥).
 *
 * **문서에만 있던 규칙이다.** 「시드를 안 넣는다」와 「표준이 옥텟으로 말하면 `octet_length`」가 어겨져도 빌드가 초록이었다.
 * 주석은 [MigrationSql] 이 걷는다 — `V2` 의 주석이 `length()` 를 설명하는데 그것을 실물로 읽으면 안 된다.
 *
 * 빠른 레인이다 — 파일만 읽는다.
 */
class MigrationTextTest {

    private val root: Path = Path.of("..").toAbsolutePath().normalize()
    private val migrations: String = MigrationSql.inOrder(root).joinToString("\n")

    @Test
    fun migrations_insert_only_into_rulebook_tables() {
        assertThat(migrations).describedAs("마이그레이션을 못 읽었다 — 경로가 바뀌었으면 MigrationSql 도 같이 고친다").isNotBlank()

        val inserted = INSERT.findAll(migrations).map { it.groupValues[1].lowercase() }.toSet()
        assertThat(inserted - RULEBOOK_TABLES.keys)
            .describedAs("마이그레이션에 시드를 넣었다 — 데모 데이터는 `local` 의 DemoSeeder 가 서비스를 불러 만든다(coding-rules.md 「마이그레이션」)")
            .isEmpty()
        // 목록이 낡으면 무엇을 봐주고 있는지 아무도 모른다.
        assertThat(RULEBOOK_TABLES.keys - inserted)
            .describedAs("허용 목록에 있는데 마이그레이션이 안 넣는 표다. 안 넣게 됐으면 RULEBOOK_TABLES 에서도 지운다")
            .isEmpty()
    }

    @Test
    fun octet_standard_columns_are_measured_in_octets() {
        OCTET_STANDARD_COLUMNS.forEach { (column, standard) ->
            assertThat(migrations)
                .describedAs("`$column` 의 길이 검사를 못 읽었다 — $standard 라 `octet_length($column)` 이어야 한다")
                .containsPattern("""octet_length\(\s*$column\s*\)""")
            assertThat(Regex("""(?<!octet_)length\(\s*$column\s*\)""").containsMatchIn(migrations))
                .describedAs("`$column` 을 글자 수(`length`)로 잰다 — $standard 다. 비ASCII 에서 옥텟보다 작게 센다")
                .isFalse()
        }
    }

    private companion object {
        val INSERT = Regex("""(?i)\binsert\s+into\s+([a-z_]+)""")

        /**
         * 마이그레이션이 행을 넣어도 되는 표. **데이터가 아니라 규약이라서다** — 코드가 그 행을 전제로 돌고, 없으면 기동 직후 첫 요청이 선다.
         * 늘리려면 그 표가 「운영에서도 같은 값으로 있어야 한다」는 근거를 값에 적는다.
         */
        val RULEBOOK_TABLES = mapOf(
            "consent_item" to "가입 동의 항목과 약관 원문. 가입 입구가 그 행을 읽는다(`V3`)",
            "refund_fee_tier" to "관람일 기준 환불 수수료 구간. 취소 입구가 그 행으로 율을 고른다(`V10`, ADR 0003)",
            "settlement_policy" to "기본 정산 수수료율. 정산이 그 행으로 계산한다(`V16`)",
            "policy_document" to "개인정보처리방침(제30조). 처리방침 화면이 그 행을 읽는다 — 없으면 발의 링크가 404 다(`V21`)",
        )

        /**
         * 바깥 표준이 **옥텟**으로 길이를 정한 컬럼. 이름·제목처럼 우리가 글자 수로 정한 상한은 `length` 가 맞다(`G7b` 실측 —
         * `display_name`·`title`, ASCII 뿐인 `key_value`·`request_hash` 가 `length` 를 쓴다).
         */
        val OCTET_STANDARD_COLUMNS = mapOf(
            "email" to "RFC 5321 — 주소 254 옥텟",
        )
    }
}
