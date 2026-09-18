package com.projectticket.ticket

import com.projectticket.ticket.audit.AuditLog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.DelegatingPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * DB 가 필요한 테스트의 바탕. 컨테이너를 테스트가 직접 띄운다.
 *
 * 손으로 띄운 로컬 컨테이너에 붙으면 Docker Desktop 을 끄는 순간 테스트가 전부 실패하고,
 * 앞선 테스트가 남긴 데이터에 결과가 좌우된다. CI 에서는 아예 붙을 DB 가 없다.
 *
 * 컨테이너는 테스트마다 새로 뜨지 않는다. 빈으로 한 번 띄우고 Spring 컨텍스트 캐시와 함께 JVM 이 끝날 때까지 쓴다.
 * `withReuse(true)` 는 `~/.testcontainers.properties` 에 `testcontainers.reuse.enable=true` 가 있을 때만 먹는다.
 *
 * `db` 태그가 레인을 가른다(`build.gradle.kts`). 이 바탕을 상속하면 느린 레인(`integrationTest`)으로 가고,
 * 빠른 레인(`test`)은 컨테이너를 한 번도 안 띄운다. 상속하지 않고 DB 를 쓰면 빠른 레인에서 곧바로 빨개진다.
 *
 * `@AutoConfigureMockMvc` 를 바탕에 두는 이유는 컨텍스트 캐시다 — 클래스마다 붙이면 같은 설정인데 컨텍스트가 갈린다.
 * Redis 도 같이 띄운다 — 세션이 거기 산다(ADR 0004, `20a`). fork 별 DB 분리는 그것이 필요한 청크(13)에서 더한다.
 */
@SpringBootTest(properties = ["ticket.scheduling.enabled=false"])
@AutoConfigureMockMvc
@Transactional
@Tag("db")
@Import(PostgresTestBase.Containers::class)
abstract class PostgresTestBase {

    /**
     * 인증을 스레드에서 걷어낸다. `@Transactional` 은 데이터만 되돌린다 — 로그인 컨트롤러가 `SecurityContextHolder` 에 심은 값은
     * 스레드에 남고, MockMvc 테스트들이 스레드를 나눠 쓰기 때문에 다음 테스트 클래스가 인증된 상태로 시작한다.
     * 빠뜨렸을 때 깨지는 것이 남의 클래스라 바탕에 둔다. 둘 다 비운다 — 한쪽만 비우면 다른 쪽이 남아 같은 증상이 난다.
     */
    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
        TestSecurityContextHolder.clearContext()
    }

    @Autowired private lateinit var redisConnections: RedisConnectionFactory

    /**
     * 앞 테스트가 남긴 세션을 지운다. `@Transactional` 은 DB 만 되돌려서 Redis 는 그대로 남고,
     * 세션은 계정 이름으로 색인된다 — 테스트들이 같은 이메일을 쓰면 남이 남긴 세션을 자기 것으로 센다.
     */
    @BeforeEach
    fun flushRedis() {
        val connection = redisConnections.connection
        try {
            connection.serverCommands().flushDb()
        } finally {
            connection.close()
        }
    }

    @Autowired private lateinit var auditCleanup: JdbcClient

    @Autowired private lateinit var auditTxManager: PlatformTransactionManager

    /**
     * 앞 테스트가 따로 커밋한 감사 기록을 걷어낸다.
     *
     * 시도 기록([AuditLog.Kind.ATTEMPT])은 `REQUIRES_NEW` 라 **테스트 롤백에 안 쓸린다.** 그게 그 구분의 목적이라 고칠 것이 아니고,
     * 대신 다음 테스트로 넘어간다. **지우는 것도 별도 트랜잭션이어야 한다** — 테스트 트랜잭션 안에서 지우면 삭제까지 같이 롤백된다.
     *
     * 뒤가 아니라 앞에서 지운다. 뒤에서 지우려면 아직 커밋 안 된 이 테스트의 행을 다른 트랜잭션이 지우려 드는 모양이 돼서 잠금에 걸린다.
     * 남으면 깨지는 것이 **남의 테스트**라 원인을 찾을 실마리가 없다. 그래서 각 테스트가 아니라 바탕에 둔다.
     *
     * **트리거를 트랜잭션 안에서만 끈다**(`V3` 의 보존 3년 가드). 안 끄면 이 정리가 그 가드에 막혀서 테스트가 전부 빨개진다 —
     * 실제로 그랬고, 그것이 가드가 제 역할을 한다는 증거다. `set local` 이라 이 트랜잭션이 끝나면 되돌아가고,
     * **가드 자체는 [com.projectticket.ticket.audit.AuditImmutabilityTest] 가 따로 잰다** — 여기서 끈다고 검증이 비는 자리가 없다.
     */
    @BeforeEach
    fun purgeCommittedAuditLogs() {
        TransactionTemplate(auditTxManager)
            .apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
            .executeWithoutResult {
                auditCleanup.sql("set local session_replication_role = 'replica'").update()
                auditCleanup.sql("delete from audit_log").update()
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Containers {

        /** `docker-compose.yml` 과 같은 이미지를 쓴다. 버전이 갈리면 테스트가 통과해도 운영에서 깨진다. */
        @Bean
        @ServiceConnection
        fun postgres(): PostgreSQLContainer =
            PostgreSQLContainer("postgres:17-alpine").withReuse(true)

        /**
         * 테스트에서만 bcrypt 비용을 4 로 낮춘다. 운영은 그대로 10 이다(`SecurityConfig`).
         * `DelegatingPasswordEncoder` 를 그대로 쓴다 — 저장값에 `{bcrypt}` 접두가 붙어서 접두를 안 읽는 인코더로 바꾸면 시드 계정 로그인이 깨진다.
         */
        /**
         * 세션 저장소(ADR 0004). `@ServiceConnection(name = "redis")` 가 호스트·포트를 꽂는다 — 이름을 적어야 한다.
         * 이미지 이름으로 알아보는 길은 `GenericContainer<Nothing>` 에서 안 먹는다(실제로 빈을 못 찾았다).
         * Testcontainers 2.x 에 Redis 전용 모듈이 없어서 코어의 `GenericContainer` 를 쓴다.
         */
        @Bean
        @ServiceConnection(name = "redis")
        fun redis(): GenericContainer<Nothing> =
            GenericContainer<Nothing>("redis:7-alpine").apply {
                withExposedPorts(6379)
                withReuse(true)
            }

        @Bean
        @Primary
        fun testPasswordEncoder(): PasswordEncoder =
            DelegatingPasswordEncoder("bcrypt", mapOf("bcrypt" to BCryptPasswordEncoder(4)))
    }
}
