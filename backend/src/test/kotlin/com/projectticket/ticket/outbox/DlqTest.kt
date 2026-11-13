package com.projectticket.ticket.outbox

import com.projectticket.ticket.ConcurrencyTestBase
import com.projectticket.ticket.Waits
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate

/**
 * 못 먹는 사건이 줄을 막지 않나(29의 닫힘 조건 절반, `D11`).
 *
 * **막히면 소비자 하나의 결함이 발행자를 멈춘다.** 오프셋이 안 넘어가서 그 뒤 사건이 영영 안 읽히고,
 * 메일도 정산도 그 자리에서 선다 — `D11` 이 「소비자 하나가 발행자를 안 멈춘다」고 정한 바로 그 자리다.
 *
 * 깨진 메시지를 그대로 넣는다. 진짜 소비자 둘이 파싱에서 죽고, **다시 시도하지 않고** `.DLT` 로 간다 —
 * 다시 읽어도 깨져 있다(`I17-7`). 사건 탓의 다른 실패는 2초 × 3회 뒤, DB 일시 오류는 무제한 지수다(`ConsumerErrorHandling`).
 * **가짜 리스너를 안 만든다** — 재는 것이 「우리 소비자가 죽었을 때」라 그 경로 그대로여야 한다.
 */
@Import(DlqTest.DeadLetterSpy::class)
class DlqTest : ConcurrencyTestBase() {

    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var spy: DeadLetterSpy

    @Test
    fun a_message_nobody_can_eat_ends_up_in_the_dead_letter_topic() {
        spy.received.clear()

        kafka.send(EventTopics.RESERVATION, EventTopics.keyOf(AGGREGATE), BROKEN).join()

        // 첫 판은 소비자가 파티션을 받는 시간까지 얹혀 넉넉히 기다린다. 그 시간 자체가 「얼마나 오래 막히나」의 상한이다.
        Waits.until("죽은 편지가 DLT 로 간다", Duration.ofSeconds(40)) { spy.received.any { it.contains(MARKER) } }
    }

    @Test
    fun a_broken_envelope_skips_the_retries() {
        // 먼저 하나를 보내 소비자가 붙은 것을 확인한다 — 붙는 시간을 아래 시간에서 뺀다.
        spy.received.clear()
        kafka.send(EventTopics.RESERVATION, EventTopics.keyOf(AGGREGATE), BROKEN).join()
        Waits.until("소비자가 붙었다", Duration.ofSeconds(40)) { spy.received.any { it.contains(MARKER) } }

        val marker = "dlq-test-fast-${System.nanoTime()}"
        kafka.send(EventTopics.RESERVATION, EventTopics.keyOf(AGGREGATE), """{"not":"an envelope","marker":"$marker"}""").join()

        // 2초 × 3회를 탔으면 6초를 넘는다. 이 배선(`setBackOffFunction`)이 빠지면 여기가 빨갛다(마무리 36차 I3).
        Waits.until("재시도 없이 DLT 로 간다", Duration.ofSeconds(5)) { spy.received.any { it.contains(marker) } }
    }

    @Test
    fun the_queue_keeps_moving_after_a_poisoned_message() {
        spy.received.clear()
        kafka.send(EventTopics.RESERVATION, EventTopics.keyOf(AGGREGATE), BROKEN).join()
        Waits.until("먼저 것이 DLT 로 간다", Duration.ofSeconds(40)) { spy.received.any { it.contains(MARKER) } }

        // 막힌 뒤에 보낸 것이 도착하면 오프셋이 넘어갔다는 뜻이다. 안 넘어가면 이 메시지는 영원히 안 읽힌다.
        val after = """{"broken":true,"marker":"$AFTER_MARKER"}"""
        kafka.send(EventTopics.RESERVATION, EventTopics.keyOf(AGGREGATE), after).join()

        Waits.until("뒤 것도 DLT 로 간다", Duration.ofSeconds(40)) { spy.received.any { it.contains(AFTER_MARKER) } }
    }

    /** DLT 를 지켜보는 자리. 그룹이 따로라 진짜 소비자와 안 겹친다 */
    @TestConfiguration(proxyBeanMethods = false)
    class DeadLetterSpy {

        val received: MutableList<String> = CopyOnWriteArrayList()

        @KafkaListener(
            topics = [EventTopics.RESERVATION + KafkaTopicsConfig.DEAD_LETTER_SUFFIX],
            groupId = "dlq-test",
        )
        fun on(message: String) {
            received += message
        }
    }

    private companion object {
        const val AGGREGATE = 990_002L
        const val MARKER = "dlq-test-marker"
        const val AFTER_MARKER = "dlq-test-after"

        /** 봉투가 아니다 — 소비자가 파싱에서 죽는다. 「소비자가 먹을 수 없는 것」의 가장 단순한 모양이다 */
        const val BROKEN = """{"not":"an envelope","marker":"$MARKER"}"""
    }
}
