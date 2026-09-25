package com.projectticket.ticket

import com.projectticket.ticket.reservation.SeatHoldService
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 화면이 고르게 하는 좌석 수와 서버가 받는 좌석 수가 같은가(`41-1`, 마무리 9차 독립 리뷰).
 *
 * 두 벌이다 — 화면 `SeatMap.MAX_SEATS`(TS)와 서버 `SeatHoldService.MAX_SEATS_PER_HOLD`(Kotlin). 갈리면 화면이 5석을
 * 고르게 두고 서버가 422 `over-limit` 으로 받는다 — 사용자는 고를 수 있던 것을 못 산다. 언어가 달라 컴파일이 못 묶어서
 * **화면 파일을 글자로 읽는다.** 서버 쪽은 상수라 컴파일이 든다(`AppDbConstraintTest` 가 앱↔DB 를 묶은 것과 같은 모양).
 *
 * 빠른 레인이다. `seat-map.tsx` 는 `build.gradle.kts` 의 `inputs.files` 와 `verify-fingerprint.sh` backend 레인에 걸려 있어야
 * 화면만 고친 커밋에서도 돈다.
 */
class SeatLimitConsistencyTest {

    private val root: Path = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun the_screen_and_the_server_allow_the_same_number_of_seats() {
        val source = Files.readString(root.resolve("frontend/src/components/seat-map.tsx"))
        val found = MAX_SEATS.findAll(source).map { it.groupValues[1].toInt() }.toList()

        // 0개면 못 읽은 것이고, 둘이면 어느 쪽이 화면을 막는지 모른다 — 둘 다 「갈렸다」가 아니라 「못 읽었다」다.
        assertThat(found)
            .describedAs("seat-map.tsx 에서 `export const MAX_SEATS = N` 을 하나만 읽어야 한다 — 이름이나 꼴이 바뀌었으면 이 정규식도 같이 고친다")
            .hasSize(1)
        assertThat(found.single())
            .describedAs("화면과 서버의 한 번 선점 상한이 갈렸다 — 화면이 고르게 둔 좌석을 서버가 422 로 받는다(ADR 0003)")
            .isEqualTo(SeatHoldService.MAX_SEATS_PER_HOLD)
    }

    private companion object {
        val MAX_SEATS = Regex("""export const MAX_SEATS = (\d+)""")
    }
}
