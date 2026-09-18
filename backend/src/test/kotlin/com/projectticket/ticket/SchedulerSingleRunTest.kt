package com.projectticket.ticket

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 인스턴스가 여럿이어도 스케줄러가 하나만 도나(33의 닫힘 조건 절반, `D4`).
 *
 * **강제 지점이 테스트인 이유**는 락이 Redis 키 하나라 제약을 걸 자리가 없어서다.
 * 여기서 재는 것은 셋이다 — 동시에 들어오면 하나만 돈다, 끝나면 다음 사람이 돈다,
 * 그리고 **남의 락을 안 지운다**(지우면 그 뒤로는 전부가 같이 돈다).
 *
 * 세션이 인스턴스를 넘어가는 쪽은 `SharedSessionTest` 가 앱을 두 번 띄워 잰다.
 */
class SchedulerSingleRunTest : PostgresTestBase() {

    @Autowired lateinit var lock: SchedulerLock
    @Autowired lateinit var redis: StringRedisTemplate

    @Test
    fun only_one_of_three_instances_runs() {
        val ran = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(INSTANCES)

        val results = (1..INSTANCES).map {
            pool.submit<Boolean> {
                start.await()
                lock.runExclusively(NAME) {
                    ran.incrementAndGet()
                    // 일이 끝나기 전에 남이 들어오는지 본다. 바로 끝나면 셋이 줄줄이 성공해도 안 보인다.
                    Thread.sleep(200)
                } != null
            }
        }
        start.countDown()

        val winners = results.count { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(winners).describedAs("셋이 같이 돌면 입장 속도가 설정값의 세 배가 된다").isEqualTo(1)
        assertThat(ran.get()).isEqualTo(1)
    }

    @Test
    fun the_next_round_gets_the_lock() {
        lock.runExclusively(NAME) { }

        // 끝나면 놓는다. 안 놓으면 TTL 30초 동안 아무도 못 돌아서 스윕이 멈춘다.
        assertThat(lock.runExclusively(NAME) { "돌았다" }).isEqualTo("돌았다")
    }

    @Test
    fun a_failure_still_releases_the_lock() {
        runCatching { lock.runExclusively<Unit>(NAME) { throw IllegalStateException("스윕이 죽었다") } }

        // 한 회가 죽었다고 다음 회까지 막히면 안 된다.
        assertThat(lock.runExclusively(NAME) { "다음 회" }).isEqualTo("다음 회")
    }

    @Test
    fun releasing_never_takes_someone_elses_lock() {
        // 일이 TTL 보다 길어진 모양을 만든다: 내 락이 만료되고 남이 같은 이름을 잡았다.
        val result = lock.runExclusively(NAME, ttl = Duration.ofMillis(200)) {
            Thread.sleep(400)
            redis.opsForValue().set("lock:$NAME", "남의-토큰")
            "끝"
        }

        assertThat(result).isEqualTo("끝")
        // 무조건 DEL 했으면 남이 일하는 중에 락이 사라지고, 그 뒤로는 전부가 같이 돈다.
        assertThat(redis.opsForValue().get("lock:$NAME")).isEqualTo("남의-토큰")
    }

    private companion object {
        const val NAME = "test-scheduler"
        const val INSTANCES = 3
    }
}
