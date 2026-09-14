package com.projectticket.ticket.venue

import com.projectticket.ticket.PostgresTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 좌석과 소속의 제약이 실제로 막는가.
 *
 * **서비스가 아니라 SQL 을 직접 친다.** 여기 걸린 것은 전부 강제 지점 2위(DB 제약)고,
 * 앱을 거쳐 재면 앱 검증이 먼저 막아서 제약이 도는지를 못 본다(`D14` 축 2).
 */
class SeatUniquenessTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient

    private var hallId: Long = 0

    @BeforeEach
    fun createHall() {
        val venueId = jdbc.sql("insert into venue (name, address) values ('테스트홀', '서울') returning venue_id")
            .query(Long::class.java).single()
        hallId = jdbc.sql("insert into hall (venue_id, name) values (:venueId, '1관') returning hall_id")
            .param("venueId", venueId).query(Long::class.java).single()
    }

    @Test
    fun same_position_twice_is_rejected() {
        insertSeat("F1-A", "A", 1)

        assertThatThrownBy { insertSeat("F1-A", "A", 1) }
            .describedAs("같은 자리가 둘이면 회차 복제가 두 배로 늘어난다")
            .isInstanceOf(DuplicateKeyException::class.java)
    }

    @Test
    fun same_position_in_another_hall_is_fine() {
        insertSeat("F1-A", "A", 1)
        val other = jdbc.sql("insert into hall (venue_id, name) select venue_id, '2관' from hall where hall_id = :id returning hall_id")
            .param("id", hallId).query(Long::class.java).single()

        val seatId = jdbc.sql(
            "insert into seat (hall_id, section, row_label, seat_number) values (:hall, 'F1-A', 'A', 1) returning seat_id",
        ).param("hall", other).query(Long::class.java).single()

        assertThat(seatId).isPositive()
    }

    /**
     * **제약 위반은 테스트 하나에 하나다.** 위반이 나면 Postgres 가 그 트랜잭션을 어보트시켜서,
     * 같은 테스트의 다음 문장이 `25P02`(current transaction is aborted)로 죽는다 —
     * 그러면 둘째 단언이 재려던 제약이 아니라 어보트를 잰다.
     */
    @Test
    fun korean_section_is_rejected() {
        // 형식을 안 걸면 `A구역`·`1층A` 가 섞여 들어와서 등급 매핑이 어느 표기를 쓰는지가 회차마다 갈린다.
        assertThatThrownBy { insertSeat("A구역", "A", 1) }.hasStackTraceContaining("seat_section_format_check")
    }

    @Test
    fun lowercase_section_is_rejected() {
        assertThatThrownBy { insertSeat("f1-a", "A", 1) }.hasStackTraceContaining("seat_section_format_check")
    }

    @Test
    fun numeric_row_label_is_rejected() {
        assertThatThrownBy { insertSeat("F1-A", "1", 1) }.hasStackTraceContaining("seat_row_label_format_check")
    }

    @Test
    fun seat_number_zero_is_rejected() {
        assertThatThrownBy { insertSeat("F1-A", "A", 0) }.hasStackTraceContaining("seat_number_positive_check")
    }

    @Test
    fun audience_cannot_join_an_organizer() {
        val organizerId = insertOrganizer()
        val audience = insertAccount("audience@test.local", "audience")

        // 앱 검증만 두면 소속을 넣는 입구가 늘 때 빠뜨린다 — 빠뜨리면 권한 없는 계정에 기획사 화면이 열린다.
        assertThatThrownBy { join(organizerId, audience) }
            .hasStackTraceContaining("역할이 organizer 여야 한다")
    }

    @Test
    fun organizer_can_join_and_role_cannot_be_lowered_afterwards() {
        val organizerId = insertOrganizer()
        val member = insertAccount("organizer@test.local", "organizer")
        join(organizerId, member)

        // 들어올 때만 보면 뒤늦게 역할을 내려서 같은 구멍이 열린다.
        assertThatThrownBy {
            jdbc.sql("update account set role = 'audience' where account_id = :id").param("id", member).update()
        }.hasStackTraceContaining("역할을 내릴 수 없다")
    }

    private fun insertSeat(section: String, rowLabel: String, number: Int): Long =
        jdbc.sql(
            """
            insert into seat (hall_id, section, row_label, seat_number)
            values (:hall, :section, :rowLabel, :number)
            returning seat_id
            """,
        )
            .param("hall", hallId).param("section", section).param("rowLabel", rowLabel).param("number", number)
            .query(Long::class.java).single()

    private fun insertOrganizer(): Long =
        jdbc.sql("insert into organizer (code, name) values ('test-org', '테스트기획') returning organizer_id")
            .query(Long::class.java).single()

    private fun insertAccount(email: String, role: String): Long =
        jdbc.sql(
            """
            insert into account (email, password_hash, display_name, role)
            values (:email, 'x', '이름', :role)
            returning account_id
            """,
        ).param("email", email).param("role", role).query(Long::class.java).single()

    private fun join(organizerId: Long, accountId: Long) =
        jdbc.sql("insert into organizer_member (organizer_id, account_id) values (:org, :acc)")
            .param("org", organizerId).param("acc", accountId).update()
}
