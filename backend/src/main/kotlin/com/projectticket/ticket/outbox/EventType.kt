package com.projectticket.ticket.outbox

import com.fasterxml.jackson.annotation.JsonValue

/**
 * 아웃박스로 나가는 사건의 이름(`D11` 카탈로그). **소비자가 있는 것만 있다** — 「누가 받나」가 비면 이 목록에 못 든다.
 *
 * `outbox_type_check` 가 같은 값 목록을 들고, 문서와의 대조는 `EventCatalogTest` 다(`D14` 「열거값을 어디에 두나」).
 * 이름은 계약이라 바꾸면 새 사건이다(`D11` 「버전」).
 *
 * **봉투에는 [code] 가 실린다**(`@JsonValue`, `I11-5`). 없으면 Jackson 이 상수 이름(`RESERVATION_RESERVED`)을 써서
 * 카탈로그의 `reservation.reserved` 와 다른 글자가 브로커에 나간다 — 우리 소비자는 같은 enum 으로 되읽어 못 느꼈다.
 */
enum class EventType(@get:JsonValue val code: String, val aggregateType: AggregateType) {
    RESERVATION_RESERVED("reservation.reserved", AggregateType.RESERVATION),
    RESERVATION_CANCELLED("reservation.cancelled", AggregateType.RESERVATION),
    PERFORMANCE_CLOSED("performance.closed", AggregateType.PERFORMANCE),
    PERFORMANCE_CANCELLED("performance.cancelled", AggregateType.PERFORMANCE);

    companion object {
        /** 소비자가 아는 봉투 판(`D11` 「버전」). 모르는 판은 [EnvelopeVersions] 가 건너뛴다 */
        const val SUPPORTED_VERSION = 1

        fun of(code: String): EventType =
            entries.firstOrNull { it.code == code } ?: error("모르는 사건 이름이다: $code")
    }
}

/** 파티션 키의 앞자리(28). 한 집합체의 사건이 순서를 지킨다. 봉투에는 [code] 가 실린다([EventType] 과 같은 이유) */
enum class AggregateType {
    RESERVATION, PERFORMANCE;

    @get:JsonValue
    val code: String get() = name.lowercase()
}
