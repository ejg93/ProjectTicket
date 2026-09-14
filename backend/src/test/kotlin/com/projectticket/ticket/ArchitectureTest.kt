package com.projectticket.ticket

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

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
     * 상태 코드는 `error` 패키지만 정한다(D14 「예외」). 서비스·컨트롤러가 `HttpStatus` 로 분기하면
     * `D5` 규약과 대조할 대상이 흩어진다. 컨트롤러의 `ResponseEntity.status(CREATED)` 처럼 성공 코드는 예외다 —
     * 여기서 막는 것은 `error` 밖에서 `ProblemDetail` 을 만드는 것이다.
     */
    @ArchTest
    val problemDetailsOnlyInErrorPackage: ArchRule = noClasses()
        .that().resideOutsideOfPackage("..error..")
        .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.http.ProblemDetail")
}
