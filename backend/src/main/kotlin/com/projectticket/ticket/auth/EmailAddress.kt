package com.projectticket.ticket.auth

import jakarta.validation.Constraint
import jakarta.validation.Payload
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Size
import kotlin.reflect.KClass

/**
 * 이메일 주소 규칙. 이 애너테이션이 그 규칙의 유일한 출처다.
 *
 * 254 는 표준이 정한 값이다 — RFC 5321 §4.5.3.1.3 이 경로를 꺾쇠 포함 256 옥텟으로 제한한다.
 * DB 제약 `account_email_length_check` 가 같은 값을 든다. 앱 검증은 배치·시드·psql 로 들어오면 안 걸려서 DB 가 더 낮은 자리다.
 */
@Email
@Size(max = 254)
@Constraint(validatedBy = [])
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class EmailAddress(
    val message: String = "이메일 주소 형식이 아니거나 너무 길다",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)
