package com.projectticket.ticket.web

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * 목록 상한 보정과 정렬 허용 목록(`D5` 「목록 조회」). 빠른 레인 — 타입만 만든다.
 *
 * 전에는 `EventListTest`(컨테이너 레인)만 이 타입을 거쳐서 빠른 레인의 변이 시험이 **변이 13 개를 하나도 못 덮었다**(`G4`).
 * 부등호 하나가 `size=10000` 을 열어도 컨테이너를 띄워야 보였다.
 */
class PagingTest {

    @Test
    fun size_and_page_are_clamped_into_the_allowed_range() {
        assertThat(Paging(size = 10_000).size).isEqualTo(Paging.MAX_SIZE)
        assertThat(Paging(size = 0).size).isEqualTo(1)
        assertThat(Paging(page = -1).page).isZero()
        assertThat(Paging(page = Int.MAX_VALUE).page).isEqualTo(Paging.MAX_PAGE)
        assertThat(Paging().size).isEqualTo(Paging.DEFAULT_SIZE)
    }

    @Test
    fun offset_is_page_times_size() {
        val paging = Paging(page = 3, size = 20, sort = "starts_at")
        assertThat(paging.offset).isEqualTo(60)
        assertThat(paging.sort).isEqualTo("starts_at")
    }

    @Test
    fun sort_maps_an_allowed_field_to_its_column_and_direction() {
        assertThat(OrderBy.of("starts_at,desc", ALLOWED, "starts_at").sql).isEqualTo("p.starts_at desc")
        assertThat(OrderBy.of(" title , ASC ", ALLOWED, "starts_at").sql).isEqualTo("p.title asc")
        // 방향을 빼면 오름차순, 값이 없으면 기본값
        assertThat(OrderBy.of("title", ALLOWED, "starts_at").sql).isEqualTo("p.title asc")
        assertThat(OrderBy.of("title,", ALLOWED, "starts_at").sql).isEqualTo("p.title asc")
        assertThat(OrderBy.of(null, ALLOWED, "starts_at,desc").sql).isEqualTo("p.starts_at desc")
    }

    @Test
    fun a_field_outside_the_list_is_rejected_not_passed_to_sql() {
        assertThatThrownBy { OrderBy.of("password;drop", ALLOWED, "starts_at") }
            .isInstanceOfSatisfying(TicketException::class.java) { assertThat(it.code).isEqualTo(ErrorCode.VALIDATION_FAILED) }
    }

    @Test
    fun a_misspelled_direction_is_rejected_not_defaulted() {
        // `decs` 가 조용히 오름차순이 되면 화면이 거꾸로 그려진 줄 모른다
        assertThatThrownBy { OrderBy.of("starts_at,decs", ALLOWED, "starts_at") }
            .isInstanceOfSatisfying(TicketException::class.java) { assertThat(it.code).isEqualTo(ErrorCode.VALIDATION_FAILED) }
    }

    private companion object {
        val ALLOWED = mapOf("starts_at" to "p.starts_at", "title" to "p.title")
    }
}
