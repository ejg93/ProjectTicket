package com.projectticket.ticket.reservation

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 발권. 확정된 예매의 좌석마다 티켓 번호 하나를 만든다 — 결제 승인 트랜잭션 안에서 부른다(`PaymentTransitionService.confirm`).
 * 확정과 발권이 한 트랜잭션이어야 「확정됐는데 표가 없는 예매」가 안 남는다.
 *
 * 번호는 `YYYYMMDD-XXXXXX`(identifier-rules). 날짜는 KST 발권일, 난수 6자는 `SecureRandom` — `Random` 은 앞의 출력 몇 개로 다음 값이 계산된다.
 * 글자는 `2-9` 와 `O`·`I` 를 뺀 32자다. 전화로 번호를 불러줄 때 잘못 듣는 것을 줄인다.
 */
@Service
class TicketService(private val jdbc: JdbcClient) {

    private val random = SecureRandom()

    /**
     * @return 발권한 티켓 수. 이미 발권된 좌석은 건너뛴다(`ticket_reservation_seat_id_key`) — 같은 확정이 두 번 와도 표가 두 장 되지 않는다
     */
    @Transactional
    fun issue(reservationId: Long, issuedAt: OffsetDateTime): Int {
        val date = issuedAt.atZoneSameInstant(KST).toLocalDate()
        val seats = jdbc.sql(
            """
            select rs.reservation_seat_id
              from reservation_seat rs
             where rs.reservation_id = :id
               and not exists (select 1 from ticket t where t.reservation_seat_id = rs.reservation_seat_id)
             order by rs.reservation_seat_id
            """,
        ).param("id", reservationId).query(Long::class.java).list().filterNotNull()

        seats.forEach { issueOne(it, date) }
        return seats.size
    }

    /**
     * 번호 충돌은 유일 인덱스가 판정하고 다시 뽑는다. `32^6 ≈ 10억` 이라 드물지만 0 이 아니다.
     * **예외로 받지 않는다** — 유일 위반은 트랜잭션을 어보트시켜서(`25P02`) 뒤 문장이 못 돈다. `on conflict do nothing` 의 0행이 「다시」다.
     * 세 번 실패하면 오류다(identifier-rules) — 그쯤이면 난수가 아니라 코드가 틀린 것이다.
     */
    private fun issueOne(reservationSeatId: Long, date: LocalDate) {
        repeat(MAX_ATTEMPTS) {
            val inserted = jdbc.sql(
                """
                insert into ticket (reservation_seat_id, ticket_number)
                values (:seat, :number)
                on conflict (ticket_number) do nothing
                """,
            )
                .param("seat", reservationSeatId)
                .param("number", ticketNumber(date))
                .update()
            if (inserted == 1) return
        }
        throw IllegalStateException("티켓 번호를 ${MAX_ATTEMPTS}번 뽑아도 겹쳤다: reservation_seat_id=$reservationSeatId")
    }

    private fun ticketNumber(date: LocalDate): String {
        val suffix = CharArray(RANDOM_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return "${date.format(DATE)}-${String(suffix)}"
    }

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /** `0`·`O`, `1`·`I` 를 뺀 32자. `ticket_ticket_number_format_check` 가 같은 집합을 든다 */
        const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        const val RANDOM_LENGTH = 6
        const val MAX_ATTEMPTS = 3

        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
