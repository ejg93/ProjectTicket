package com.projectticket.ticket.auth

import jakarta.validation.Constraint
import jakarta.validation.Payload
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import kotlin.reflect.KClass

/**
 * 비밀번호 규칙(`D9`). 이 애너테이션이 그 규칙의 유일한 출처다.
 *
 * 길이와 문자 집합만 본다. 조합 강제 금지는 NIST SP 800-63B 의 SHALL NOT 이다.
 * 최소 15자 — 같은 문서 Rev.4 가 비밀번호가 단독 인증수단이면 15자를 SHALL 로 요구하고, 여기는 MFA 가 없다.
 * ASCII 제한은 bcrypt 의 72바이트 절단 구간을 아예 안 만들려는 것이다(한글은 글자당 3바이트).
 *
 * 가입과 비밀번호 변경이 각자 규칙을 들면 한쪽만 고치는 날 갈린다. 그래서 하나로 모았다.
 *
 * 블록리스트 대조(유출·사전 단어·연속 문자·문맥 단어)는 [NoCommonPassword] 가 한다(`3b`).
 *
 * **`@ReportAsSingleViolation` 을 뺐다.** 그것이 있으면 무엇을 어겼든 아래 `message` 하나로 뭉쳐서
 * 「15자가 안 된다」와 「흔한 비밀번호다」가 같은 말로 나간다 — 고칠 방법이 다른데 같은 문구면 사용자가 헤맨다(`D5`).
 * 대신 구성 제약마다 문구를 직접 적는다.
 */
@Size(min = 15, max = 64, message = "비밀번호는 15~64자여야 한다")
@Pattern(regexp = "^[\\x20-\\x7E]+$", message = "비밀번호는 ASCII 출력 가능 문자만 쓴다")
@NoCommonPassword
@Constraint(validatedBy = [])
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Password(
    val message: String = "비밀번호는 15~64자의 ASCII 출력 가능 문자여야 한다",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)
