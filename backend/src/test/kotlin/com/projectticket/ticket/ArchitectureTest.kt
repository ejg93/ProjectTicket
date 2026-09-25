package com.projectticket.ticket

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody

/**
 * `coding-rules.md`(D14)가 글로만 적어 둔 계층 규칙을 기계가 지킨다.
 *
 * 빠른 레인이다 — 클래스 파일만 읽고 컨테이너를 안 띄운다. 규칙을 더할 때는 그 규칙이 무엇을 막는지 주석에 적는다.
 */
@AnalyzeClasses(packages = ["com.projectticket.ticket"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {

    /**
     * 패키지끼리 순환하지 않는다. 자원 단위로 판 패키지가 서로를 부르기 시작하면 모듈을 쪼갤 때 막히는 자리가 된다.
     * `error` 는 모두가 부르지만 아무도 안 부르는 잎이라 순환에 안 걸린다.
     */
    @ArchTest
    val noPackageCycles: ArchRule = slices().matching("com.projectticket.ticket.(*)..").should().beFreeOfCycles()

    /**
     * 의존은 `payment → reservation → event → settlement` 로 아래로만 간다(`D14` 「패키지」, `G7a`).
     * 순환 금지만으로는 **거꾸로 선 한 방향**(`settlement` 가 `event` 를 부르는 것)을 못 막는다 — 순환이 아니라서다.
     * 넷 밖의 패키지(`outbox`·`error`·`queue` …)와 오가는 의존은 여기서 안 본다.
     */
    @ArchTest
    val resourcePackagesDependDownward: ArchRule = layeredArchitecture().consideringOnlyDependenciesInLayers()
        .layer("payment").definedBy("..ticket.payment..")
        .layer("reservation").definedBy("..ticket.reservation..")
        .layer("event").definedBy("..ticket.event..")
        .layer("settlement").definedBy("..ticket.settlement..")
        .whereLayer("payment").mayNotBeAccessedByAnyLayer()
        .whereLayer("reservation").mayOnlyBeAccessedByLayers("payment")
        .whereLayer("event").mayOnlyBeAccessedByLayers("payment", "reservation")
        .whereLayer("settlement").mayOnlyBeAccessedByLayers("payment", "reservation", "event")

    /**
     * `PUT`·`PATCH` 를 안 쓴다(`D5` — 상태는 하위 경로에 `POST`). 고칠 자원이 생기면 `merge-patch+json` 을 먼저 정하고 이 규칙을 푼다(`G7a`).
     */
    @ArchTest
    val noPutOrPatchEndpoints: ArchRule = noMethods()
        .should().beAnnotatedWith(PatchMapping::class.java)
        .orShould().beAnnotatedWith(PutMapping::class.java)

    /**
     * 앱 시계를 안 읽는다(`D7` — 만료·마감 판정은 SQL `now()`, 앱의 `Clock` 은 주입받는 계산기에만). `G7a`.
     * 예외는 이름으로 적는다 — 넷 다 판정이 아니거나 비교 상대가 앱 시계로 찍힌 값이다. 사유는 `time-rules.md`.
     */
    @ArchTest
    val noAppClockReads: ArchRule = classes()
        .that().resideInAPackage("com.projectticket.ticket..")
        .should(
            object : ArchCondition<JavaClass>("not read the app clock (java.time *.now(), System.currentTimeMillis)") {
                override fun check(javaClass: JavaClass, events: ConditionEvents) {
                    if (javaClass.name.substringBefore('$') in APP_CLOCK_ALLOWED) return
                    javaClass.methodCallsFromSelf
                        .filter { call ->
                            val target = call.target
                            (target.owner.packageName == "java.time" && target.name == "now") ||
                                (target.owner.name == "java.lang.System" && target.name == "currentTimeMillis")
                        }
                        .forEach { events.add(SimpleConditionEvent.violated(it, "${it.description} — 앱 시계다. 판정은 SQL now(), 계산은 주입받은 Clock")) }
                }
            },
        )

    /**
     * 웹 애너테이션은 `*Controller` 에만 붙는다. 서비스가 `@RequestMapping` 을 들면 웹을 아는 서비스가 되고,
     * 배치·이벤트 소비자에서 재사용할 때 어색해진다.
     */
    @ArchTest
    val onlyControllersCarryWebAnnotations: ArchRule = classes()
        .that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController::class.java)
        .or().areAnnotatedWith(org.springframework.web.bind.annotation.RequestMapping::class.java)
        .should().haveSimpleNameEndingWith("Controller")

    /**
     * 오류 본문은 `error` 패키지만 만든다(D14 「예외」). 다른 자리에서 `ProblemDetail` 을 조립하면 같은 오류가 형태만 다르게 두 벌 나간다.
     * 잡는 것은 `ProblemDetail` 타입 의존뿐이다 — 상태 코드 결정은 아래 규칙이 본다.
     */
    @ArchTest
    val problemDetailsOnlyInErrorPackage: ArchRule = noClasses()
        .that().resideOutsideOfPackage("..error..")
        .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.http.ProblemDetail")

    /**
     * 오류 상태 코드는 `error` 패키지만 정한다. 필터·서비스가 `HttpStatus` 로 응답 상태를 박으면
     * 본문 없는 401·403 이 나가고(RFC 9110·9457 위반) `D5` 표와 대조할 자리가 흩어진다.
     * 컨트롤러는 예외다 — 성공 코드(201·204)를 `ResponseEntity.status` 로 정하는 것이 그 자리라서다.
     */
    @ArchTest
    val statusCodesOnlyInErrorPackageOrControllers: ArchRule = noClasses()
        .that().resideOutsideOfPackage("..error..")
        .and().haveSimpleNameNotEndingWith("Controller")
        .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.http.HttpStatus")

    /** 시간대 없는 시각을 안 든다(`D7`). `LocalDateTime` 은 받는 쪽이 시간대를 짐작하게 만들고, 세 인스턴스가 다르게 짐작한다 */
    @ArchTest
    val noLocalDateTime: ArchRule = noClasses()
        .should().dependOnClassesThat().haveFullyQualifiedName("java.time.LocalDateTime")

    /**
     * `@RequestBody` 에 `@Valid` 가 없으면 검증이 **조용히 안 돈다**(`D9`). 새 입구가 생길 때 빠뜨리는 자리라 문서(5위)에서 테스트(4위)로 내린다.
     */
    @ArchTest
    val requestBodiesAreValidated: ArchRule = methods()
        .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Controller")
        .should(
            object : ArchCondition<JavaMethod>("have @Valid on every @RequestBody parameter") {
                override fun check(method: JavaMethod, events: ConditionEvents) {
                    method.parameters
                        .filter { it.isAnnotatedWith(RequestBody::class.java) && !it.isAnnotatedWith(Valid::class.java) }
                        .forEach { events.add(SimpleConditionEvent.violated(method, "${method.fullName} 의 @RequestBody 에 @Valid 가 없다")) }
                }
            },
        )

    private companion object {
        /** 앱 시계를 읽어도 되는 클래스. 늘리려면 `time-rules.md` 「앱 시계의 예외」에 사유를 먼저 적는다 */
        val APP_CLOCK_ALLOWED = setOf(
            "com.projectticket.ticket.demo.DemoSeeder",
            "com.projectticket.ticket.health.HealthController",
            "com.projectticket.ticket.auth.AbsoluteSessionTimeoutFilter",
            "com.projectticket.ticket.payment.MockPaymentGateway",
        )
    }
}
