package com.projectticket.ticket.queue

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * 순번 → 예상 대기(`D12`). 초당 [QueueService.ADMIT_PER_SECOND] 명이 들어가는 올림 나눗셈이다.
 *
 * **단위 층이다**(`D8`, 청크 `I3-2`). 전에는 이 경계를 `QueueRankTest` 가 Redis·DB 위에서만 쟀고,
 * 21번째를 만들려고 앞줄을 스무 명 채워야 했다 — 틀리는 자리는 경계 하나인데 값이 컨테이너 둘이었다.
 */
class QueueEtaTest {

    @ParameterizedTest
    @CsvSource(
        // 한 초치(20명)까지는 1초다. 첫 사람도 0 이 아니다 — 0 은 「이미 들어갔다」로 읽힌다
        "1, 1",
        "20, 1",
        // 스물한 번째는 둘째 초다. **올림이 아니면 여기서 1 이 나온다**
        "21, 2",
        "40, 2",
        "41, 3",
    )
    fun the_rank_rounds_up_to_whole_seconds(rank: Long, expected: Long) {
        assertThat(QueueService.etaSecondsFor(rank)).isEqualTo(expected)
    }

    @Test
    fun the_wait_never_shrinks_as_the_line_grows() {
        val waits = (1L..100L).map { QueueService.etaSecondsFor(it) }

        // 화면이 2초마다 다시 묻는다(`D12`). 앞으로 갔는데 예상이 늘면 사용자가 줄을 떠난다.
        assertThat(waits).isSorted()
    }
}
