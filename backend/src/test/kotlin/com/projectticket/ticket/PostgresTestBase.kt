package com.projectticket.ticket

import org.junit.jupiter.api.Tag
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
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
 * fork 별 DB 분리·Redis 컨테이너는 그것이 필요한 청크(13·21)에서 더한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Tag("db")
@Import(PostgresTestBase.Containers::class)
abstract class PostgresTestBase {

    @TestConfiguration(proxyBeanMethods = false)
    class Containers {

        /** `docker-compose.yml` 과 같은 이미지를 쓴다. 버전이 갈리면 테스트가 통과해도 운영에서 깨진다. */
        @Bean
        @ServiceConnection
        fun postgres(): PostgreSQLContainer =
            PostgreSQLContainer("postgres:17-alpine").withReuse(true)
    }
}
