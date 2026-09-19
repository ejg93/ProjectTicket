package com.projectticket.ticket

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `state-machines.md`(`D3`) 의 전이표와 DB 전이 트리거가 같은가(청크 `I3-1`).
 *
 * **한쪽만 고치면 아무 일도 안 난다.** 트리거에만 전이를 더하면 문서를 믿은 다음 사람이 「할 수 없는 전이」로 알고 짜고,
 * 문서에만 더하면 그 경로를 짠 코드가 운영에서 `할 수 없는 예매 상태 전이다` 로 죽는다 — 둘 다 컴파일도 테스트도 초록이다.
 * 점검 3차 전에는 이 대조를 사람이 한 번 한 것이 전부였다.
 *
 * **마이그레이션을 판 순서로 읽고 마지막 `create or replace` 만 본다** — `V17`·`V19` 가 `V5`·`V7` 의 함수를 갈아 끼웠다.
 *
 * **못 보는 모양**: 트리거가 `in ('a','b')`·`= 'a'` 말고 다른 꼴로 전이를 적으면 파서가 못 잡는다.
 * 그때는 짝이 비어 이 테스트가 먼저 빨개진다 — 조용히 통과하지 않는다.
 *
 * 빠른 레인이다 — 문서와 마이그레이션 파일만 읽는다.
 */
class StateMachineDocTest {

    private val root: Path = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun the_document_and_the_triggers_allow_the_same_transitions() {
        val doc = docTransitions()

        MACHINES.forEach { machine ->
            val fromTrigger = triggerTransitions(machine)
            assertThat(fromTrigger)
                .describedAs("`${machine}_status_transition` 에서 전이를 하나도 못 읽었다. 함수가 다른 꼴로 적혔으면 이 파서도 같이 고친다")
                .isNotEmpty()
            assertThat(doc[machine])
                .describedAs("`state-machines.md` 의 `$machine` 절에 전이표가 없다")
                .isNotNull()

            assertThat(doc.getValue(machine))
                .describedAs("`$machine` — 문서의 전이표와 전이 트리거가 갈렸다. 상태를 더할 때 셋(check·트리거·표)을 같이 본다")
                .containsExactlyInAnyOrderElementsOf(fromTrigger)
        }
    }

    @Test
    fun every_transition_trigger_has_a_table_in_the_document() {
        val inMigrations = migrationsInOrder()
            .flatMap { FUNCTION_NAME.findAll(it).map { match -> match.groupValues[1] } }
            .toSet()

        // 전이 트리거를 새로 만들었으면 **문서에 표가 서야 한다.** 여기서 멈추는 것이 그 자리다.
        assertThat(inMigrations)
            .describedAs("전이 트리거가 생겼는데 `state-machines.md` 에 전이표가 없다")
            .isSubsetOf(MACHINES)
    }

    /** 문서의 `### 전이표` 들. 상태기계 이름 → `에서 → 로` 짝 */
    private fun docTransitions(): Map<String, Set<Pair<String, String>>> = buildMap {
        var machine: String? = null
        var inTable = false

        Files.readString(root.resolve("doc/reference/state-machines.md")).lines().forEach { line ->
            when {
                line.trim() == "### 전이표" -> inTable = true
                line.startsWith("#") -> {
                    if (line.startsWith("## ")) machine = MACHINES.firstOrNull { line.contains("`$it`") }
                    inTable = false
                }
                inTable && machine != null && line.startsWith("|") -> rowTransitions(line)?.let { pairs ->
                    // `!!` 의 근거: 바로 위 조건이 null 이 아닌 것만 남긴다. 스마트 캐스트는 var 라 안 걸린다.
                    merge(machine!!, pairs) { old, new -> old + new }
                }
            }
        }
    }

    /** `| 에서 | 로 | … |` 한 줄. 머리·구분선과 생성 행(`—`)은 짝이 아니다 */
    private fun rowTransitions(line: String): Set<Pair<String, String>>? {
        val cells = line.split("|").map { it.trim() }
        if (cells.size < 4 || cells[1] == "에서" || cells[1].startsWith("-")) return null

        val from = states(cells[1])
        val to = states(cells[2])
        if (from.isEmpty() || to.isEmpty()) return null

        return from.flatMap { one -> to.map { one to it } }.toSet()
    }

    /** `` `held`·`paying` `` → `[held, paying]`. 생성 행의 `—` 는 상태가 아니다 */
    private fun states(cell: String): List<String> =
        cell.split("·").mapNotNull { STATE.find(it)?.groupValues?.get(1) }

    /** 트리거 함수가 허용하는 짝. 마지막 `create or replace` 만 본다 */
    private fun triggerTransitions(machine: String): Set<Pair<String, String>> {
        val body = migrationsInOrder()
            .mapNotNull { Regex("""create or replace function ${machine}_status_transition\(\)(.*?)language plpgsql""", RegexOption.DOT_MATCHES_ALL).find(it) }
            .lastOrNull()
            ?.groupValues?.get(1)
            ?: return emptySet()

        return ALLOWED.findAll(body).flatMap { match ->
            val from = match.groupValues[1]
            val to = if (match.groupValues[3].isNotEmpty()) listOf(match.groupValues[3])
            else VALUE.findAll(match.groupValues[2]).map { it.groupValues[1] }.toList()
            to.map { from to it }
        }.toSet()
    }

    /** `V2` 가 `V10` 보다 먼저다. 이름순으로 읽으면 갈아 끼운 판이 앞에 온다 */
    private fun migrationsInOrder(): List<String> =
        Files.walk(root.resolve("backend/src/main/resources/db/migration")).use { paths ->
            paths.asSequence()
                .filter { it.fileName.toString().startsWith("V") && it.toString().endsWith(".sql") }
                // `!!` 의 근거: 위 filter 가 `V` 로 시작하는 것만 남겼고 이름 규약이 `V<숫자>__` 다(`EnumConstraintTest` 와 같은 자리).
                .sortedBy { VERSION.find(it.fileName.toString())!!.groupValues[1].toInt() }
                .map(Files::readString)
                .toList()
        }

    private companion object {
        /** 전이표를 둔 상태기계. 결제·환불은 전이가 없다 — 행이 한 번 서고 끝이라 트리거도 없다 */
        val MACHINES = listOf("reservation", "performance")

        /** `old.status = 'held' and new.status in ('paying', …)` 또는 `… new.status = 'open'` */
        val ALLOWED = Regex("""old\.status\s*=\s*'(\w+)'\s+and\s+new\.status\s*(?:in\s*\(([^)]*)\)|=\s*'(\w+)')""")
        val FUNCTION_NAME = Regex("""create or replace function (\w+)_status_transition\(\)""")
        val STATE = Regex("""`(\w+)`""")
        val VALUE = Regex("""'([^']*)'""")
        val VERSION = Regex("""^V(\d+)""")
    }
}
