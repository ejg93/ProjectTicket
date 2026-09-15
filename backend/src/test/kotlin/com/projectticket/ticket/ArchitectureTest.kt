package com.projectticket.ticket

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
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import jakarta.validation.Valid
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
}
