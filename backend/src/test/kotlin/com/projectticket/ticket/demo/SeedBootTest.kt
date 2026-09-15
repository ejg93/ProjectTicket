package com.projectticket.ticket.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import com.projectticket.ticket.PostgresTestBase

/**
 * **빈 DB 에서 데모 데이터까지 뜨는가**(12). 시드가 마이그레이션이 아니라 서비스를 밟으므로, 이것이 초록이면
 * 좌석 복제(9)·등급 매핑(11)·정책 박제(27)가 **기동 경로에서 한 번씩 돌았다는 뜻**이다.
 *
 * 컨텍스트가 하나 더 뜬다(`ticket.demo.enabled=true`) — 그 값이 캐시 키를 가른다. 그 비용을 치르는 이유는
 * **이 테스트가 재려는 것이 「러너가 실제로 도나」**라서다. 러너를 손으로 부르면 그 물음이 사라진다.
 */
@SpringBootTest(properties = ["ticket.scheduling.enabled=false", "ticket.demo.enabled=true"])
@Tag("db")
@Import(PostgresTestBase.Containers::class)
class SeedBootTest {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var seeder: DemoSeeder

    @Test
    fun the_demo_data_is_there_after_boot() {
        assertThat(count("organizer where code = '${DemoSeeder.ORGANIZER_CODE}'")).isOne()
        assertThat(count("account where email like '%@demo.local'")).isEqualTo((DemoSeeder.AUDIENCE_COUNT + 2).toLong())
        // 홀 둘 × 구역 둘 × 500석
        assertThat(count("seat s join hall h on h.hall_id = s.hall_id join venue v on v.venue_id = h.venue_id where v.name = '데모아트홀'"))
            .isEqualTo(2_000)
        assertThat(count("event e join organizer o on o.organizer_id = e.organizer_id where o.code = '${DemoSeeder.ORGANIZER_CODE}'")).isEqualTo(2)
    }

    @Test
    fun one_performance_per_event_is_open_and_its_seats_are_copied() {
        // 연 회차 둘(공연마다 하나) + 안 연 회차 둘 — 여는 절차를 화면에서 밟아 볼 자리가 남아 있어야 한다.
        assertThat(count("$DEMO_PERFORMANCES and p.status = 'open'")).isEqualTo(2)
        assertThat(count("$DEMO_PERFORMANCES and p.status = 'draft'")).isEqualTo(2)
        // 좌석 복제가 기동 경로에서 실제로 돌았다 — 구역 넷 중 둘씩, 홀당 1천석.
        assertThat(count("performance_seat ps, $DEMO_PERFORMANCES and p.performance_id = ps.performance_id")).isEqualTo(2_000)
        // 정책이 오픈 때 박제됐다(`D21`).
        assertThat(count("$DEMO_PERFORMANCES and p.status = 'open' and p.settlement_policy_id is not null")).isEqualTo(2)
    }

    @Test
    fun running_the_seeder_again_changes_nothing() {
        val before = count("account where email like '%@demo.local'")

        seeder.run(DefaultApplicationArguments())

        // Flyway 가 주던 「한 번만」이 없으니 러너가 스스로 든다 — 표식 기획사가 있으면 통째로 건너뛴다.
        assertThat(count("account where email like '%@demo.local'")).isEqualTo(before)
        assertThat(count("organizer where code = '${DemoSeeder.ORGANIZER_CODE}'")).isOne()
    }

    private fun count(fromWhere: String): Long =
        jdbc.sql("select count(*) from $fromWhere").query(Long::class.java).single()

    companion object {
        /**
         * **셈을 데모 기획사로 좁히는 조각.** 이 테스트는 롤백 레인이 아니라 재사용 컨테이너에 커밋하므로,
         * `performance` · `performance_seat` 를 통째로 세면 커밋 레인의 다른 테스트가 남긴 회차에 빨개진다.
         */
        private const val DEMO_PERFORMANCES =
            "performance p, event e, organizer o " +
                "where e.event_id = p.event_id and o.organizer_id = e.organizer_id and o.code = '${DemoSeeder.ORGANIZER_CODE}'"
    }
}
