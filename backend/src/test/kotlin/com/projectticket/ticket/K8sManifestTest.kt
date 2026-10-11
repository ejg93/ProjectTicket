package com.projectticket.ticket

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * k8s 매니페스트가 **무중단의 전제를 지키나**(`36`). `ComposeContractTest` 와 같은 자리 — 파일을 글자로 읽어 클러스터가 필요 없다.
 *
 * | 무엇 | 틀리면 |
 * |---|---|
 * | readiness 경로 | `liveness` 를 물으면 DB 가 끊긴 대가 엔드포인트에 남는다(35a) |
 * | `replicas: 3` | 한 대면 롤링 동안 받을 대가 없다 |
 * | `terminationGracePeriodSeconds` ≥ 25 | 앱의 graceful 상한(20초)보다 짧으면 진행 중인 요청을 SIGKILL 이 끊는다 |
 * | `imagePullPolicy: Never` + 태그 일치 | kind 는 `kind load` 한 이미지만 본다 — 태그가 갈리면 `ErrImageNeverPull` 로 선다 |
 */
class K8sManifestTest {

    private val root: Path = Path.of("..").toAbsolutePath().normalize()

    /** 주석은 걷어낸다 — 설명 줄의 단어가 검사에 걸리면 안 된다 */
    private val app = Files.readString(root.resolve("k8s/base/app.yaml"))
        .lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
    private val up = Files.readString(root.resolve("scripts/k8s-up.sh"))

    @Test
    fun the_app_deployment_keeps_the_zero_downtime_premises() {
        // 아래 단언이 다 「값이 있다」라 못 읽으면 빨강이지만, Deployment 를 다른 파일로 옮기면 그 사실을 먼저 말한다(`G3`).
        assertThat(app).describedAs("k8s/base/app.yaml 에서 Deployment 를 못 읽었다 — 옮겼으면 이 검사도 옮긴다").contains("kind: Deployment")

        assertThat(app).contains("path: /actuator/health/readiness")
        assertThat(app).contains("replicas: 3")

        val grace = Regex("""terminationGracePeriodSeconds:\s*(\d+)""").find(app)?.groupValues?.get(1)?.toInt()
        assertThat(grace).describedAs("terminationGracePeriodSeconds 를 못 읽었다").isNotNull()
        assertThat(grace).describedAs("앱의 graceful 상한 20초보다 넉넉해야 한다").isGreaterThanOrEqualTo(MIN_GRACE_SECONDS)
    }

    @Test
    fun the_image_is_the_one_the_script_loads() {
        assertThat(app).contains("imagePullPolicy: Never")

        val built = Regex("""IMAGE=(\S+)""").find(up)?.groupValues?.get(1)
        assertThat(built).describedAs("scripts/k8s-up.sh 에서 IMAGE= 를 못 읽었다").isNotNull()
        assertThat(app).describedAs("매니페스트의 이미지가 k8s-up.sh 가 굽는 태그와 다르다").contains("image: $built")
    }

    private companion object {
        /** 앱의 `timeout-per-shutdown-phase: 20s` + 엔드포인트 전파 여유 */
        const val MIN_GRACE_SECONDS = 25
    }
}
