package com.projectticket.ticket.event

import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 좌석 현황 조회(`D20`). 로그인 없이 본다(`SecurityConfig.PUBLIC_GET_PATHS`).
 *
 * `ETag` 는 버전이고 `Cache-Control: no-cache` 는 「써도 되지만 매번 재검증하라」다(`D5` 「헤더」).
 * 재검증은 손으로 한다 — `WebRequest.checkNotModified` 가 응답에 헤더를 직접 쓰는데 `ResponseEntity` 가 또 쓰면 `ETag` 가 둘이 된다.
 */
@RestController
@RequestMapping("/api/performances/{performanceId}/seats")
class SeatController(private val seatQuery: SeatQuery) {

    @GetMapping
    fun seats(
        @PathVariable performanceId: Long,
        @RequestHeader(name = "If-None-Match", required = false) ifNoneMatch: String?,
    ): ResponseEntity<SeatQuery.SeatMap> {
        val etag = "\"${seatQuery.version(performanceId)}\""
        return if (matches(ifNoneMatch, etag)) {
            revalidated(HttpStatus.NOT_MODIFIED, etag).build()
        } else {
            revalidated(HttpStatus.OK, etag).body(seatQuery.seatMap(performanceId))
        }
    }

    /**
     * 델타(`D20`). 첫 로드는 전체, 그 뒤 폴링은 이쪽이다 — 2천 석을 매번 보내는 대신 바뀐 좌석만 보낸다.
     *
     * `ETag` 를 안 단다. 바뀐 것만 담은 응답이라 **같은 `since` 로 또 물어도 같은 답이 아니다** — 재검증할 것이 없다.
     */
    @GetMapping("/changes")
    fun changes(@PathVariable performanceId: Long, @RequestParam since: Long): SeatQuery.SeatChanges =
        seatQuery.changes(performanceId, since)

    /** 304 에도 `ETag` 를 다시 싣는다 — RFC 9110 §15.4.5 는 200 에 실었을 헤더를 304 에도 보내라고 한다 */
    private fun revalidated(status: HttpStatus, etag: String): ResponseEntity.BodyBuilder =
        ResponseEntity.status(status).eTag(etag).cacheControl(CacheControl.noCache())

    /** RFC 9110 §13.1.2 — `*` 는 전부, 나머지는 쉼표 목록. 약한 표시(`W/`)는 재검증에서 같은 값으로 친다(§8.8.3.2) */
    private fun matches(ifNoneMatch: String?, etag: String): Boolean =
        ifNoneMatch?.split(',')?.map { it.trim().removePrefix("W/") }?.any { it == "*" || it == etag } ?: false
}
