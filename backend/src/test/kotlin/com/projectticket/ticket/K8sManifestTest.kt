package com.projectticket.ticket

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * k8s 매니페스트가 **무중단의 전제를 지키나**(`36`). `ComposeContractTest` 와 같은 자리 — 파일을 글자로 읽어 클러스터가 필요 없다.
 * 차트(`37`)의 템플릿이 아니라 **값이 박힌 렌더 결과**(`k8s/chart/snapshot.yaml`)를 읽는다 — 템플릿에는 `{{ .Values… }}` 만 있다.
 * 스냅샷이 렌더와 같은지는 `verify.sh` tools 레인과 CI `backend` 잡이 본다.
 *
 * | 무엇 | 틀리면 |
 * |---|---|
 * | readiness 경로 | `liveness` 를 물으면 DB 가 끊긴 대가 엔드포인트에 남는다(35a) |
 * | 대수 셋 — HPA 하한 또는 `replicas` | 한 대면 롤링 동안 받을 대가 없다 |
 * | `maxUnavailable: 0` | 롤링이 새 대가 준비되기 전에 옛 대를 내린다 |
 * | `preStop` | 엔드포인트 제거가 퍼지기 전에 SIGTERM 이 와서 닫힌 대로 요청이 간다(`38`, `doc/notes/rollout.md`) |
 * | HPA 대상이 `app` | 이름이 갈리면 HPA 가 아무것도 안 늘린다 |
 * | `terminationGracePeriodSeconds` ≥ 25 | 앱의 graceful 상한(20초)보다 짧으면 진행 중인 요청을 SIGKILL 이 끊는다 |
 * | `imagePullPolicy: Never` + 태그 일치 | kind 는 `kind load` 한 이미지만 본다 — 태그가 갈리면 `ErrImageNeverPull` 로 선다 |
 */
class K8sManifestTest {

    private val root: Path = Path.of("..").toAbsolutePath().normalize()

    /** 스냅샷의 문서들. 주석은 걷어낸다 — 설명 줄의 단어가 검사에 걸리면 안 된다 */
    private val docs = Files.readString(root.resolve("k8s/chart/snapshot.yaml"))
        .split(Regex("""(?m)^---$"""))
        .map { doc -> doc.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n") }
    private val app = docOf("Deployment", "app")
    private val hpa = docOf("HorizontalPodAutoscaler", "app")
    private val up = Files.readString(root.resolve("scripts/k8s-up.sh"))

    @Test
    fun the_app_deployment_keeps_the_zero_downtime_premises() {
        // 아래 단언이 다 「값이 있다」라 못 읽으면 빨강이지만, Deployment 를 다른 파일로 옮기면 그 사실을 먼저 말한다(`G3`).
        assertThat(app).describedAs("k8s/chart/snapshot.yaml 에서 앱 Deployment 를 못 읽었다 — 이름을 바꿨거나 스냅샷을 안 만들었다").contains("kind: Deployment")

        assertThat(app).contains("path: /actuator/health/readiness")
        assertThat(app).contains("maxUnavailable: 0")
        assertThat(app).describedAs("preStop 이 없다 — 롤링 동안 닫힌 대로 요청이 간다(38)").contains("preStop:")

        // HPA 가 켜지면 Deployment 는 `replicas` 를 안 쓴다 — 대수의 하한은 HPA 가 든다.
        if (hpa.isEmpty()) {
            assertThat(app).contains("replicas: 3")
        } else {
            assertThat(hpa).contains("minReplicas: 3")
            assertThat(hpa).describedAs("HPA 가 앱 Deployment 를 안 가리킨다")
                .containsPattern("""scaleTargetRef:\s+apiVersion: apps/v1\s+kind: Deployment\s+name: app""")
            assertThat(app).describedAs("HPA 가 켜졌는데 replicas 를 박으면 helm upgrade 마다 HPA 의 대수를 되돌린다")
                .doesNotContain("replicas:")
        }

        val grace = Regex("""terminationGracePeriodSeconds:\s*(\d+)""").find(app)?.groupValues?.get(1)?.toInt()
        assertThat(grace).describedAs("terminationGracePeriodSeconds 를 못 읽었다").isNotNull()
        assertThat(grace).describedAs("앱의 graceful 상한 20초보다 넉넉해야 한다").isGreaterThanOrEqualTo(MIN_GRACE_SECONDS)
    }

    /** compose 는 1g(`ComposeContractTest`) — 숫자가 다른 것이 결정이다. 여기는 HPA 가 대수로 받는다(`35i`) */
    @Test
    fun app_pods_have_a_memory_limit() {
        assertThat(app).contains("limits:").contains("memory: 768Mi")
    }

    @Test
    fun the_image_is_the_one_the_script_loads() {
        assertThat(app).contains("imagePullPolicy: Never")

        val built = Regex("""IMAGE=(\S+)""").find(up)?.groupValues?.get(1)
        assertThat(built).describedAs("scripts/k8s-up.sh 에서 IMAGE= 를 못 읽었다").isNotNull()
        assertThat(app).describedAs("매니페스트의 이미지가 k8s-up.sh 가 굽는 태그와 다르다").contains("image: $built")
    }

    @Test
    fun the_web_image_bakes_the_backend_origin_at_build_time() {
        val web = docOf("Deployment", "web")
        assertThat(web).describedAs("k8s/chart/snapshot.yaml 에서 화면 Deployment 를 못 읽었다(36a)").contains("kind: Deployment")
        // 로그인 화면은 백엔드를 안 부른다 — 앱이 롤링 중이어도 화면 대가 엔드포인트에서 안 빠진다.
        assertThat(web).contains("path: /login")

        // `rewrites()` 의 목적지는 `next build` 때 굳는다(`stack.md`). 실행 env 로는 `/api` 프록시가 안 바뀌므로 빌드 인자여야 한다.
        assertThat(Files.readString(root.resolve("frontend/Dockerfile")))
            .describedAs("frontend/Dockerfile 이 BACKEND_ORIGIN 을 빌드 인자로 안 받는다 — 프록시가 localhost:8080 으로 굳는다")
            .contains("ARG BACKEND_ORIGIN")
        assertThat(up).describedAs("k8s-up.sh 가 화면을 앱 Service 주소로 안 굽는다").contains("--build-arg BACKEND_ORIGIN=http://app:8080")
    }

    private fun docOf(kind: String, name: String): String =
        docs.firstOrNull { it.contains("kind: $kind") && it.contains(Regex("""(?m)^ {2}name: $name$""")) }.orEmpty()

    private companion object {
        /** 앱의 `timeout-per-shutdown-phase: 20s` + 엔드포인트 전파 여유 */
        const val MIN_GRACE_SECONDS = 25
    }
}
