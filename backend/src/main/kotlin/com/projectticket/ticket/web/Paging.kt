package com.projectticket.ticket.web

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException

/**
 * 목록 조회의 `page`·`size`·`sort`(`D5` 「목록 조회」).
 *
 * **컨트롤러가 셋을 따로 안 받는다.** 상한 보정이 이 타입의 생성자에 있어서 **타입이 있다는 것 자체가
 * 「상한을 거쳤다」는 뜻**이다 — 컨트롤러마다 `size` 를 손으로 자르면 새 목록이 생길 때 빠뜨린다.
 *
 * `sort` 는 여기서 안 푼다. 허용 목록이 자원마다 달라서 [OrderBy] 가 자원 쪽에서 받는다.
 */
class Paging(page: Int = 0, size: Int = DEFAULT_SIZE, val sort: String? = null) {

    /** 음수 페이지는 0 이다. 400 으로 세우지 않는 이유는 **주소를 손으로 고친 사람**이 보는 값이라서다 */
    val page: Int = page.coerceAtLeast(0)

    /** **최대 100**(`D5`). 넘겨 보내면 잘라서 준다 — 요청을 세우면 목록이 통째로 안 보인다 */
    val size: Int = size.coerceIn(1, MAX_SIZE)

    val offset: Int get() = page * size

    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100
    }
}

/**
 * 정렬. **허용 목록으로만 받는다**(`D14` 「SQL」).
 *
 * 정렬 필드는 값이 아니라 **식별자라 바인딩이 안 된다** — `:sort` 로 넘길 수 없어서 SQL 에 글자로 박히고,
 * 그래서 받은 글자를 그대로 쓰면 그 자리가 주입 지점이 된다. 허용 목록에 있는 것만 통과시키고
 * **SQL 조각은 우리가 쓴 것을 꺼내 쓴다** — 요청의 글자가 SQL 에 닿지 않는다.
 */
class OrderBy private constructor(val sql: String) {

    companion object {
        /**
         * `필드,방향` 을 푼다. 허용 목록 밖이면 400 이다(`D5`).
         *
         * @param allowed 요청에 쓰는 이름 → `order by` 에 넣을 SQL 조각. 값이 우리 글자다
         * @param default `sort` 가 없을 때 쓸 이름
         */
        fun of(raw: String?, allowed: Map<String, String>, default: String): OrderBy {
            val (field, direction) = split(raw ?: default)
            val column = allowed[field]
                ?: throw TicketException(
                    ErrorCode.VALIDATION_FAILED,
                    "정렬할 수 없는 필드다: sort=$field",
                    mapOf(
                        "errors" to listOf(
                            mapOf("field" to "sort", "message" to "정렬 가능: ${allowed.keys.joinToString(", ")}"),
                        ),
                    ),
                )
            return OrderBy("$column $direction")
        }

        private fun split(raw: String): Pair<String, String> {
            val parts = raw.split(',', limit = 2)
            val direction = when (parts.getOrNull(1)?.trim()?.lowercase()) {
                // 방향은 둘뿐이라 목록이 아니라 `when` 이다. 모르는 값은 기본값으로 안 떨어뜨린다 —
                // `starts_at,decs` 같은 오타가 조용히 오름차순이 되면 화면이 거꾸로 그려진 줄 모른다.
                "asc", null, "" -> "asc"
                "desc" -> "desc"
                else -> throw TicketException(
                    ErrorCode.VALIDATION_FAILED,
                    "정렬 방향이 아니다: ${parts[1]}",
                    mapOf(
                        "errors" to listOf(mapOf("field" to "sort", "message" to "방향은 asc 또는 desc 다")),
                    ),
                )
            }
            return parts[0].trim() to direction
        }
    }
}
