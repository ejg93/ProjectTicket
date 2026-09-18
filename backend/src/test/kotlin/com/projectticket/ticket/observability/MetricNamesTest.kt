package com.projectticket.ticket.observability

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 지표 이름이 문서와 같은가(`D10` 「지표 — 이름 규약」, 청크 30의 닫힘 조건).
 *
 * **어긋난 이름은 아무 오류도 안 낸다.** 대시보드 패널만 조용히 비고, 그것을 알아차리는 때는 대개 사고 중이다 —
 * 그래서 사람이 두 곳을 맞추는 대신 여기서 대조한다.
 *
 * 빠른 레인이다 — 파일만 읽는다. 작업 디렉터리는 Gradle 이 `backend/` 로 두므로 저장소 루트는 한 단계 위다.
 */
class MetricNamesTest {

    private val root: Path = Path.of("..").toAbsolutePath().normalize()
    private val rules = Files.readString(root.resolve("doc/reference/observability-rules.md"))

    @Test
    fun the_document_and_the_code_hold_the_same_names() {
        assertThat(documentedNames())
            .describedAs("`D10` 의 이름 표와 `TicketMetrics` 가 갈리면 대시보드가 조용히 빈다")
            .containsExactlyInAnyOrderElementsOf(TicketMetrics.ALL)
    }

    @Test
    fun every_name_is_actually_used() {
        val sources = Files.walk(root.resolve("backend/src/main/kotlin")).use { paths ->
            paths.asSequence()
                .filter { it.toString().endsWith(".kt") }
                .joinToString("\n") { Files.readString(it) }
        }

        // 상수만 있고 아무도 안 부르면 그 지표는 영원히 0 이다 — 그림이 비는 것과 구분이 안 된다.
        TicketMetrics.ALL.forEach { name ->
            val constant = name.uppercase().replace('.', '_')
            assertThat(sources)
                .describedAs("$name 을 쓰는 자리가 없다")
                .contains("metrics.${callOf(constant)}", "const val $constant")
        }
    }

    /** `D10` 표 첫 칸의 백틱 이름들. 한 행이 둘을 들기도 한다(`reservation.confirmed` · `reservation.cancelled`) */
    private fun documentedNames(): List<String> =
        rules.lineSequence()
            .dropWhile { !it.startsWith("| 이름 |") }
            .drop(2)
            .takeWhile { it.startsWith("|") }
            .flatMap { row -> NAME.findAll(row.split("|")[1]).map { it.groupValues[1] } }
            .toList()

    /** 상수 이름 → 부르는 함수 이름. `SEAT_HOLD_ATTEMPTS` 는 `seatHoldAttempt(` 로 불린다 */
    private fun callOf(constant: String): String =
        when (constant) {
            "SEAT_HOLD_ATTEMPTS" -> "seatHoldAttempt("
            "SEAT_HOLD_LATENCY" -> "seatHoldLatency("
            "SEAT_SWEEP_EXPIRED" -> "seatSweepExpired("
            "RESERVATION_CONFIRMED" -> "reservationConfirmed("
            "RESERVATION_CANCELLED" -> "reservationCancelled("
            "QUEUE_LENGTH", "QUEUE_ACTIVE" -> "queueSizes("
            "QUEUE_ADMITTED" -> "queueAdmitted("
            "QUEUE_WAIT_SECONDS" -> "queueWaited("
            "QUEUE_GATE_REJECTED" -> "gateRejected("
            "SEAT_SNAPSHOT_BUILT" -> "snapshotBuilt("
            else -> throw AssertionError("지표를 더했으면 이 표에도 부르는 자리를 적는다: $constant")
        }

    private companion object {
        val NAME = Regex("`([a-z][a-z._]+)`")
    }
}
