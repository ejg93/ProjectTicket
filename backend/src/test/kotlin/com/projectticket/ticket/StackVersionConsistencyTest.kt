package com.projectticket.ticket

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * `stack.md` 의 버전표가 실물(빌드 파일·compose·wrapper)과 같은가(`P11`). 표는 사람이 적어서 낡고, 낡은 표를 다음 사람이 믿는다.
 *
 * 빠른 레인이다 — 파일만 읽는다. 작업 디렉터리는 Gradle 이 `backend/` 로 두므로 저장소 루트는 한 단계 위다.
 */
class StackVersionConsistencyTest {

    private val root: Path = Path.of("..").toAbsolutePath().normalize()
    private val stack = Files.readString(root.resolve("doc/reference/stack.md"))
    private val gradle = Files.readString(root.resolve("backend/build.gradle.kts"))

    @Test
    fun spring_boot_and_kotlin_match_the_build_file() {
        assertThat(gradle).contains("id(\"org.springframework.boot\") version \"${tableVersion("Spring Boot")}\"")
        assertThat(gradle).contains("kotlin(\"jvm\") version \"${tableVersion("Kotlin")}\"")
        assertThat(gradle).contains("JavaLanguageVersion.of(${tableVersion("Java")})")
    }

    @Test
    fun test_libraries_match_the_build_file() {
        assertThat(gradle).contains("org.testcontainers:testcontainers-bom:${tableVersion("Testcontainers")}")
        assertThat(gradle).contains("com.tngtech.archunit:archunit-junit6:${tableVersion("ArchUnit")}")
    }

    @Test
    fun gradle_matches_the_wrapper() {
        val wrapper = Files.readString(root.resolve("backend/gradle/wrapper/gradle-wrapper.properties"))
        assertThat(wrapper).contains("gradle-${tableVersion("Gradle")}-bin.zip")
    }

    @Test
    fun container_images_match_compose() {
        val compose = Files.readString(root.resolve("docker-compose.yml"))
        assertThat(compose).contains("image: postgres:${tableVersion("PostgreSQL")}")
        assertThat(compose).contains("image: redis:${tableVersion("Redis")}")
        assertThat(compose).contains("image: prom/prometheus:${tableVersion("Prometheus")}")
        assertThat(compose).contains("image: grafana/grafana:${tableVersion("Grafana")}")
        // 테스트 컨테이너도 같은 이미지다 — 갈리면 테스트가 통과해도 운영에서 깨진다.
        assertThat(Files.readString(root.resolve("backend/src/test/kotlin/com/projectticket/ticket/PostgresTestBase.kt")))
            .contains("\"postgres:${tableVersion("PostgreSQL")}\"")
            .contains("\"redis:${tableVersion("Redis")}\"")
    }

    @Test
    fun ci_jdk_and_node_match_the_table() {
        val ci = Files.readString(root.resolve(".github/workflows/ci.yml"))
        assertThat(ci).contains("java-version: '${tableVersion("Java")}'")
        assertThat(ci).contains("node-version: '${tableVersion("Node")}'")
    }

    /** 버전표의 한 행 — `| 대상 | 버전 | …` 에서 둘째 칸 */
    private fun tableVersion(subject: String): String {
        val row = stack.lineSequence().firstOrNull { it.startsWith("| $subject |") }
            ?: throw AssertionError("stack.md 버전표에 「$subject」 행이 없다")
        return row.split("|")[2].trim()
    }
}
