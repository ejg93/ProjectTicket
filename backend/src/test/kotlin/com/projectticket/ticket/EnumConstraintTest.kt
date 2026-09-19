package com.projectticket.ticket

import com.projectticket.ticket.auth.AccountRole
import com.projectticket.ticket.auth.AccountStatus
import com.projectticket.ticket.event.PerformanceSeatStatus
import com.projectticket.ticket.notification.NotificationStatus
import com.projectticket.ticket.outbox.AggregateType
import com.projectticket.ticket.outbox.EventType
import com.projectticket.ticket.payment.PaymentStatus
import com.projectticket.ticket.reservation.CancelledBy
import com.projectticket.ticket.reservation.ReservationStatus
import com.projectticket.ticket.settlement.SettlementLineKind
import com.projectticket.ticket.settlement.SettlementStatus
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * DB 가 닫아 둔 값 목록과 Kotlin 열거형이 같은가(`D14` 「열거값을 어디에 두나」, 청크 `I2-1`).
 *
 * **갈리면 컴파일은 초록이고 런타임에 갈라진다.** 값을 열거형에만 더하면 삽입이 `check` 에 걸려 죽고,
 * `check` 에만 더하면 그 행을 읽을 때 `of()` 가 죽는다 — 둘 다 그 값이 실제로 흐를 때까지 안 보인다.
 *
 * **마이그레이션을 판 순서로 읽고 `drop constraint` 를 반영한다.** `V17` 이 `performance_status_check` 를
 * 떨구고 `cancelled` 를 더한 판으로 다시 걸어서, 파일을 그냥 훑으면 낡은 목록이 같이 잡힌다.
 *
 * **못 보는 모양**: 이름 없는 `check`·`= any (array[…])` 꼴로 값을 닫으면 파서가 못 잡고, 「짝이 없다」를 보는 쪽도 같은 파서를 써서 같이 조용하다.
 * 오늘 마이그레이션의 `check` 는 전부 이름 붙은 꼴이라 새는 것이 없다(마무리 7차 독립 리뷰).
 *
 * 빠른 레인이다 — 파일과 열거형만 읽는다.
 */
class EnumConstraintTest {

    private val root: Path = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun every_enum_matches_the_closed_value_set_in_the_database() {
        val live = liveConstraints()

        MIRRORED.forEach { (constraint, codes) ->
            assertThat(live[constraint])
                .describedAs("`$constraint` 가 마이그레이션에 없다. 제약 이름이 바뀌었으면 이 표도 바뀐다")
                .isNotNull()
            assertThat(live.getValue(constraint))
                .describedAs("`$constraint` 와 열거형이 갈렸다 — 한쪽에만 더한 값은 런타임에야 죽는다")
                .containsExactlyInAnyOrderElementsOf(codes)
        }
    }

    @Test
    fun every_closed_value_set_is_accounted_for() {
        val unaccounted = liveConstraints().keys - MIRRORED.keys - SUBSETS.keys - WITHOUT_ENUM

        // 닫힌 값 목록을 새로 만들었으면 **열거형을 둘지 안 둘지 정해야 한다.** 여기서 멈추는 것이 그 자리다.
        assertThat(unaccounted)
            .describedAs("값 목록을 닫았는데 열거형 짝이 없다. 짝을 만들거나 `WITHOUT_ENUM` 에 이유와 함께 적는다")
            .isEmpty()
    }


    @Test
    fun a_narrower_value_set_stays_inside_its_enum() {
        val live = liveConstraints()

        SUBSETS.forEach { (constraint, codes) ->
            // 일부러 좁힌 목록이다. **같아야 하는 것이 아니라 안을 벗어나면 안 되는 것** —
            // 벗어난 값은 사건 이름을 고치면서 한쪽만 고친 흔적이다.
            assertThat(codes)
                .describedAs("`$constraint` 에 열거형 밖의 값이 있다")
                .containsAll(live.getValue(constraint))
        }
    }

    /** 제약 이름 → 닫아 둔 값 집합. `drop` 을 반영해 **마지막 판만** 남긴다 */
    private fun liveConstraints(): Map<String, List<String>> = buildMap {
        MigrationSql.inOrder(root).forEach { sql ->
            DROP.findAll(sql).forEach { remove(it.groupValues[1]) }
            CHECK_IN.findAll(sql).forEach { match ->
                val values = VALUE.findAll(match.groupValues[2]).map { it.groupValues[1] }.toList()
                put(match.groupValues[1], values)
            }
        }
    }

    private companion object {
        /** `constraint 이름 check (칸 in ('a', 'b'))`. `cancelled_by is null or …` 꼴도 받는다 */
        val CHECK_IN = Regex("""constraint\s+(\w+)\s+check\s*\(\s*(?:\w+\s+is\s+null\s+or\s+)?\w+\s+in\s*\(([^)]*)\)""")
        val DROP = Regex("""drop\s+constraint\s+(?:if\s+exists\s+)?(\w+)""")
        val VALUE = Regex("""'([^']*)'""")

        /** 열거형이 거울인 제약. 새 열거형이 생기면 여기 행이 는다 */
        val MIRRORED: Map<String, List<String>> = mapOf(
            "account_role_check" to AccountRole.entries.map { it.code },
            "account_status_check" to AccountStatus.entries.map { it.code },
            "performance_seat_status_check" to PerformanceSeatStatus.entries.map { it.code },
            "notification_status_check" to NotificationStatus.entries.map { it.code },
            "outbox_aggregate_type_check" to AggregateType.entries.map { it.code },
            "outbox_type_check" to EventType.entries.map { it.code },
            "payment_status_check" to PaymentStatus.entries.map { it.code },
            "reservation_status_check" to ReservationStatus.entries.map { it.code },
            "reservation_cancelled_by_check" to CancelledBy.entries.map { it.code },
            "settlement_line_kind_check" to SettlementLineKind.entries.map { it.code },
            "settlement_status_check" to SettlementStatus.entries.map { it.code },
        )


        /**
         * 열거형의 **부분집합**인 제약. 같지 않은 것이 의도다.
         *
         * 알림은 `performance.closed` 를 안 받는다 — 회차가 끝난 것은 관객에게 보낼 말이 아니다(`D11`).
         */
        val SUBSETS: Map<String, List<String>> = mapOf(
            "notification_event_type_check" to EventType.entries.map { it.code },
        )
        /**
         * 열거형을 **일부러 안 둔** 값 목록. 앱이 그 값을 분기에 안 써서 열거형이 값을 못 한다.
         *
         * 여기 적힌 것은 이 테스트가 못 지킨다 — 갈려도 안 걸린다. 앱이 분기하기 시작하면 열거형을 만들고 위로 옮긴다.
         */
        val WITHOUT_ENUM: Set<String> = setOf(
            // 회차 상태는 SQL 안에서만 산다(전이 트리거·조건부 UPDATE). Kotlin 쪽은 문자열을 안 비교한다.
            "performance_status_check",
            // 기획사 상태도 같다 — 지금은 조회 필터에만 쓴다.
            "organizer_status_check",
            // 동의 출처는 감사용 꼬리표다. 분기하지 않는다.
            "account_consent_source_check",
            // 환불 사유·상태는 `D6` 의 표가 출처고 앱은 삽입만 한다.
            "refund_reason_check",
            "refund_status_check",
        )
    }
}
