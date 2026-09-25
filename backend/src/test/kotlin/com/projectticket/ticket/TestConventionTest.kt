package com.projectticket.ticket

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * 레인·태그·바탕의 약속(`testing-strategy.md` 「레인」, `D8`)을 테스트 소스에 건다(`G7d`, `G5` 원장 ②).
 *
 * **문서에만 있던 규칙이다.** 「태그는 바탕이 붙인다」와 「레인 이름은 `build.gradle.kts` 와 같다」를 `P4` 가 대조한다고 적혀 있었는데
 * 그 테스트가 없었다. ProjectShop `Q30` 의 `TestConventionTest` 에서 **테스트 클래스를 ArchUnit 으로 읽는 꼴**만 가져왔다 —
 * 그쪽 규칙(`@DisplayName` 한글 평서형)은 여기 규약에 없다.
 *
 * **바이트코드로 읽는다.** 상속은 소스 정규식으로 가르면 제네릭·줄바꿈에서 틀린다. Kotlin 의 람다·중첩 클래스는
 * `Outer$…` 로 나오므로 최상위 클래스 이름으로 묶어 본다.
 *
 * 빠른 레인이다 — 클래스 파일과 문서·빌드 파일만 읽는다.
 */
class TestConventionTest {

    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.OnlyIncludeTests())
        .importPackages("com.projectticket.ticket")
    private val topLevel = classes.filter { it.isTopLevelClass }

    @Test
    fun a_test_that_touches_the_database_is_in_the_db_lane() {
        val testClasses = topLevel.filter { it.hasTests() }
        assertThat(testClasses).describedAs("테스트 클래스를 못 읽었다 — ImportOption 이나 패키지가 바뀌었다").hasSizeGreaterThan(MIN_TEST_CLASSES)

        // DB·MockMvc 를 쓰는데 바탕을 안 물려받으면 `db` 태그가 없어 빠른 레인에서 컨테이너 없이 돈다.
        val stray = testClasses
            .filter { it.usesDatabase() && !it.isOnBase() && it.name !in MANUAL_DB_TAG }
            .map { it.simpleName }
        assertThat(stray)
            .describedAs("DB·MockMvc 를 쓰는데 PostgresTestBase·ConcurrencyTestBase 를 안 물려받았다(testing-strategy.md 「태그는 바탕이 붙인다」)")
            .isEmpty()
    }

    @Test
    fun only_the_bases_and_named_exceptions_carry_a_hand_written_db_tag() {
        val handTagged = classes.filter { it.tagsDb() }.map { it.name }.toSet()
        assertThat(handTagged - BASES.map { it.name }.toSet() - MANUAL_DB_TAG.keys)
            .describedAs("`@Tag(\"db\")` 를 손으로 달았다 — 바탕을 물려받는다. 못 물려받는 사유가 있으면 MANUAL_DB_TAG 에 적는다")
            .isEmpty()
        // 목록이 낡으면 무엇을 봐주고 있는지 아무도 모른다.
        assertThat(MANUAL_DB_TAG.keys - handTagged)
            .describedAs("MANUAL_DB_TAG 에 있는데 `@Tag(\"db\")` 가 없다 — 바탕으로 옮겼으면 목록에서 지운다")
            .isEmpty()
    }

    @Test
    fun the_lane_table_matches_the_build_file() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val lanes = Files.readString(root.resolve("doc/reference/testing-strategy.md")).lineSequence()
            .dropWhile { !it.startsWith("| 레인 |") }
            .drop(2)
            .takeWhile { it.startsWith("|") }
            .map { it.split("|") }
            .toList()
        val build = Files.readString(root.resolve("backend/build.gradle.kts"))

        val docTasks = lanes.mapNotNull { GRADLEW.find(it[2])?.groupValues?.get(1) }.toSet()
        val docTags = lanes.flatMap { row -> BACKTICKED.findAll(row[4]).map { it.groupValues[1] } }.toSet()
        assertThat(docTasks).describedAs("testing-strategy.md 「| 레인 |」 표에서 명령을 못 읽었다").isNotEmpty()

        val registered = REGISTERED.findAll(build).map { it.groupValues[2] to it.groupValues[1] }.toMap()
        val buildTags = TAG_CALL.findAll(build).flatMap { QUOTED.findAll(it.groupValues[1]).map { q -> q.groupValues[1] } }.toSet()

        assertThat(docTasks - registered.keys - "test")
            .describedAs("레인 표의 명령이 build.gradle.kts 에 없다 — 태스크 이름을 바꿨으면 표도 같이 고친다")
            .isEmpty()
        assertThat(registered.filterValues { it == "Test" }.keys - docTasks)
            .describedAs("build.gradle.kts 의 Test 태스크가 레인 표에 없다 — 레인을 더했으면 표에 행을 더한다")
            .isEmpty()
        assertThat(docTags - buildTags)
            .describedAs("레인 표의 태그가 build.gradle.kts 의 includeTags·excludeTags 에 없다")
            .isEmpty()
    }

    private fun JavaClass.family(): List<JavaClass> = classes.filter { it.name == name || it.name.startsWith("$name$") }

    private fun JavaClass.hasTests(): Boolean =
        family().any { c -> c.methods.any { m -> m.annotations.any { it.rawType.name in TEST_ANNOTATIONS } } }

    private fun JavaClass.usesDatabase(): Boolean =
        family().any { c -> c.directDependenciesFromSelf.any { it.targetClass.name in DATABASE_TYPES } }

    private fun JavaClass.isOnBase(): Boolean = BASES.any { isAssignableTo(it) }

    private fun JavaClass.tagsDb(): Boolean = tryGetAnnotationOfType(Tag::class.java).map { it.value == "db" }.orElse(false)

    private companion object {
        /** 테스트 클래스는 지금 쉰이 넘는다. 이 밑이면 읽기가 틀린 것이다 */
        const val MIN_TEST_CLASSES = 30

        val BASES = listOf(PostgresTestBase::class.java, ConcurrencyTestBase::class.java)

        val TEST_ANNOTATIONS = setOf(
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.params.ParameterizedTest",
            "org.junit.jupiter.api.RepeatedTest",
            "org.junit.jupiter.api.TestFactory",
        )

        /** 이것을 쓰면 컨테이너가 필요하다. 컨테이너 정의를 빌려 쓰는 것도 같다 */
        val DATABASE_TYPES = setOf(
            "org.springframework.jdbc.core.simple.JdbcClient",
            "org.springframework.test.web.servlet.MockMvc",
            "com.projectticket.ticket.PostgresTestBase\$Containers",
        )

        /** 바탕을 못 물려받아 `@Tag("db")` 를 손으로 다는 것. 값이 사유다 — 사유가 없으면 여기 못 넣는다 */
        val MANUAL_DB_TAG = mapOf(
            "com.projectticket.ticket.auth.SharedSessionTest" to "앱을 둘 띄워 세션 공유를 잰다 — 바탕은 컨텍스트 하나에 MockMvc 하나다",
            "com.projectticket.ticket.demo.SeedBootTest" to "ticket.demo.enabled=true 로 컨텍스트가 갈린다 — 러너가 실제로 도는지가 재려는 것이다",
        )

        val GRADLEW = Regex("""`gradlew (\w+)`""")
        val BACKTICKED = Regex("`([a-z]+)`")
        val REGISTERED = Regex("""tasks\.register<(\w+)>\("(\w+)"\)""")
        val TAG_CALL = Regex("""(?:include|exclude)Tags\(([^)]*)\)""")
        val QUOTED = Regex("\"([a-z]+)\"")
    }
}
