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
        assertThat(gradle).contains("""id("org.springframework.boot") version "${tableVersion("Spring Boot")}"""")
        assertThat(gradle).contains("""kotlin("jvm") version "${tableVersion("Kotlin")}"""")
        // 플러그인 둘은 같은 판이어야 한다 — `jvm` 만 보면 `plugin.spring` 이 혼자 남는다(점검 9차).
        assertThat(gradle).contains("""kotlin("plugin.spring") version "${tableVersion("Kotlin")}"""")
        assertThat(gradle).contains("""id("io.spring.dependency-management") version "${tableVersion("dependency-management")}"""")
        assertThat(gradle).contains("JavaLanguageVersion.of(${tableVersion("Java")})")
    }

    @Test
    fun test_libraries_match_the_build_file() {
        assertThat(gradle).contains("org.testcontainers:testcontainers-bom:${tableVersion("Testcontainers")}")
        assertThat(gradle).contains("com.tngtech.archunit:archunit-junit6:${tableVersion("ArchUnit")}")
        assertThat(gradle).contains("""id("dev.detekt") version "${tableVersion("detekt")}"""")
        assertThat(gradle).contains("org.pitest:pitest-command-line:${tableVersion("pitest")}")
        assertThat(gradle).contains("org.pitest:pitest-junit5-plugin:${tableVersion("pitest-junit5-plugin")}")
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
        assertThat(compose).contains("image: nginx:${tableVersion("nginx")}")
        assertThat(compose).contains("image: apache/kafka:${tableVersion("Kafka")}")
        assertThat(compose).contains("image: prom/prometheus:${tableVersion("Prometheus")}")
        assertThat(compose).contains("image: grafana/grafana:${tableVersion("Grafana")}")
        // 테스트 컨테이너도 같은 이미지다 — 갈리면 테스트가 통과해도 운영에서 깨진다.
        assertThat(Files.readString(root.resolve("backend/src/test/kotlin/com/projectticket/ticket/PostgresTestBase.kt")))
            .contains("\"postgres:${tableVersion("PostgreSQL")}\"")
            .contains("\"redis:${tableVersion("Redis")}\"")
            .contains("\"apache/kafka:${tableVersion("Kafka")}\"")
    }

    /** 이미지를 굽는 판이 CI 판과 갈리면 CI 가 초록이어도 이미지 안에서 깨진다. `FROM` 이 여럿이라 전부 본다 */
    @Test
    fun dockerfiles_match_the_table() {
        assertThat(fromTags(dockerfile = "frontend/Dockerfile", image = "node"))
            .isNotEmpty
            .allSatisfy { assertThat(it).startsWith("${tableVersion("Node")}-") }
        assertThat(fromTags(dockerfile = "backend/Dockerfile", image = "eclipse-temurin"))
            .isNotEmpty
            .allSatisfy { assertThat(it).startsWith("${tableVersion("Java")}-") }
    }

    /** 차트는 compose 다음의 셋째 사본이다(`37`) */
    @Test
    fun chart_images_match_the_table() {
        val values = Files.readString(root.resolve("k8s/chart/values.yaml"))
        assertThat(values).contains("image: postgres:${tableVersion("PostgreSQL")}")
        assertThat(values).contains("image: redis:${tableVersion("Redis")}")
        assertThat(values).contains("image: apache/kafka:${tableVersion("Kafka")}")
    }

    @Test
    fun helm_and_playwright_match_the_table() {
        val ci = Files.readString(root.resolve(".github/workflows/ci.yml"))
        assertThat(ci).containsPattern("""uses: azure/setup-helm@\S+\s+with:\s+version: v${Regex.escape(tableVersion("Helm"))}\s""")
        assertThat(Files.readString(root.resolve("frontend/package.json")))
            .contains(""""@playwright/test": "^${tableVersion("Playwright")}"""")
    }

    /** 셸 검사기도 판을 박는다(`G13`) — 로컬 winget 과 CI 가 같은 판이어야 같은 지적을 본다 */
    @Test
    fun shellcheck_matches_the_table() {
        val ci = Files.readString(root.resolve(".github/workflows/ci.yml"))
        assertThat(ci).contains("shellcheck/releases/download/v${tableVersion("ShellCheck")}/")
    }

    /** 부하 도구도 판을 박는다 — 안 박으면 그날의 latest 로 재서 다른 판의 숫자를 견주게 된다(`35b`) */
    @Test
    fun k6_image_matches_the_table() {
        val image = "grafana/k6:${tableVersion("k6")}"
        assertThat(Files.readString(root.resolve("load/README.md"))).contains(image).doesNotContain("grafana/k6 run")
        assertThat(Files.readString(root.resolve("scripts/k8s-rollout-test.sh"))).contains(image)
    }

    @Test
    fun ci_jdk_and_node_match_the_table() {
        val ci = Files.readString(root.resolve(".github/workflows/ci.yml"))
        assertThat(ci).contains("java-version: '${tableVersion("Java")}'")
        assertThat(ci).contains("node-version: '${tableVersion("Node")}'")
    }

    @Test
    fun frontend_versions_match_package_json() {
        val pkg = Files.readString(root.resolve("frontend/package.json"))

        // **정확한 판으로 박는다**(`^` 없이). 화면은 프레임워크 판이 바뀌면 캐시 기본값과 API 가 갈려서,
        // 표가 든 수와 실물이 다르면 `frontend-rules.md` 의 캐시 절을 낡은 근거 위에서 읽게 된다.
        listOf("Next" to "next", "React" to "react").forEach { (subject, dependency) ->
            assertThat(pkg)
                .describedAs("`stack.md` 의 「$subject」 행과 `frontend/package.json` 이 갈렸다")
                .contains(""""$dependency": "${tableVersion(subject)}"""")
        }
    }

    private fun fromTags(dockerfile: String, image: String): List<String> =
        Regex("""^FROM ${Regex.escape(image)}:(\S+)""", RegexOption.MULTILINE)
            .findAll(Files.readString(root.resolve(dockerfile)))
            .map { it.groupValues[1] }
            .toList()

    /**
     * 버전표의 한 행 — `| 대상 | 버전 | …` 에서 둘째 칸.
     * 칸이 비면 `image: grafana/grafana:` 처럼 접두만 남은 문장이 compose 에 그대로 들어 있어 초록이다 — 그래서 여기서 선다(`G3`).
     */
    private fun tableVersion(subject: String): String {
        val row = stack.lineSequence().firstOrNull { it.startsWith("| $subject |") }
            ?: throw AssertionError("stack.md 버전표에 「$subject」 행이 없다")
        return row.split("|")[2].trim()
            .ifBlank { throw AssertionError("stack.md 버전표 「$subject」 행의 버전 칸을 못 읽었다 — 비었다") }
    }
}
