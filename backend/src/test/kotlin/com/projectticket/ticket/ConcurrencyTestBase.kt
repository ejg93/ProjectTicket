package com.projectticket.ticket

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 경쟁을 재는 테스트의 바탕. [PostgresTestBase] 와 같은 컨테이너 위에서 돌되 **트랜잭션이 없다.**
 *
 * 테스트가 트랜잭션을 열고 있으면 다른 스레드가 그 안의 변경을 못 보고 잠금도 안 걸려서 경쟁 자체가 안 일어난다.
 * 그래서 각 스레드가 자기 커넥션·자기 트랜잭션으로 커밋하고, 되돌릴 것이 없으니 **만든 것을 직접 지운다.**
 *
 * 애너테이션을 [PostgresTestBase] 와 똑같이 맞춘다(`@AutoConfigureMockMvc` 까지). MockMvc 를 여기서 쓸 일은 없지만
 * 컨텍스트 캐시 키가 애너테이션으로 갈리기 때문에, 하나라도 다르면 컨텍스트가 하나 더 뜨고 컨테이너도 하나 더 뜬다.
 * `@Transactional` 은 캐시 키에 안 들어가서 그것만 뺄 수 있다.
 *
 * 커넥션 풀은 기본값(Hikari 10)이다. 스레드 100 을 한 번에 놓아도 DB 에는 열이 10 씩 들어가는데,
 * 재는 것은 스레드 수가 아니라 **같은 행을 두 트랜잭션이 동시에 건드릴 때 DB 가 어떻게 하나**라서 10 으로 족하다.
 * 풀을 키우려면 속성이 붙어 컨텍스트가 갈린다 — 그 값을 치를 이유가 생길 때 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("db")
@Import(PostgresTestBase.Containers::class)
abstract class ConcurrencyTestBase {

    @Autowired protected lateinit var jdbc: JdbcClient

    @Autowired private lateinit var txManager: PlatformTransactionManager

    /**
     * 정리를 **앞뒤로 한 번씩** 한다. 뒤에서만 지우면 앞선 실행이 죽었을 때 재사용 컨테이너에 남은 행이 이번 실행을 깨뜨리고,
     * 앞에서만 지우면 이 클래스 뒤에 도는 [PostgresTestBase] 계열이 남은 행을 본다.
     *
     * **한 트랜잭션**이다. 합계 등식이 지연 트리거라 자식만 지운 채 커밋되면 거기서 터진다.
     * 순서는 `restrict` 외래키를 거슬러 올라간다 — 좌석의 포인터를 먼저 풀어야 예매가 지워지고, 예매가 계정·회차를 `restrict` 로 잡아서
     * 예매가 그다음, 회차가 공연·홀을 `restrict` 로 잡아서 회차가 그다음이다. 나머지는 `cascade` 라 부모만 지운다.
     */
    @BeforeEach
    @AfterEach
    fun purgePrefixedRows() {
        TransactionTemplate(txManager).executeWithoutResult {
            val performances = """
                select performance_id from performance where event_id in (
                    select event_id from event where organizer_id in (
                        select organizer_id from organizer where code like :prefix))
            """
            val reservations = """
                select reservation_id from reservation
                 where performance_id in ($performances)
                    or account_id in (select account_id from account where email like :prefix)
            """
            jdbc.sql("update performance_seat set status = 'available', held_until = null, reservation_id = null where performance_id in ($performances)")
                .param("prefix", "$PREFIX%").update()
            // 환불 → 결제 → 예매. 셋 다 `restrict` 라 자식부터다.
            jdbc.sql("delete from refund where payment_id in (select payment_id from payment where reservation_id in ($reservations))")
                .param("prefix", "$PREFIX%").update()
            jdbc.sql("delete from payment where reservation_id in ($reservations)").param("prefix", "$PREFIX%").update()
            jdbc.sql("delete from reservation where reservation_id in ($reservations)").param("prefix", "$PREFIX%").update()
            jdbc.sql("delete from performance where performance_id in ($performances)").param("prefix", "$PREFIX%").update()
            jdbc.sql("delete from event where organizer_id in (select organizer_id from organizer where code like :prefix)")
                .param("prefix", "$PREFIX%").update()
            jdbc.sql("delete from organizer where code like :prefix").param("prefix", "$PREFIX%").update()
            jdbc.sql("delete from venue where name like :prefix").param("prefix", "$PREFIX%").update()
            jdbc.sql("delete from account where email like :prefix").param("prefix", "$PREFIX%").update()
        }
    }

    /**
     * 스레드 `count` 를 **출발선에 모아 두고 한 번에 놓는다.** 스레드를 만드는 시간이 선점 하나보다 길어서
     * 그냥 띄우면 먼저 만들어진 스레드가 혼자 끝내고, 경쟁이 안 난 채로 통과한다.
     *
     * 결과를 [Result] 로 돌려준다 — 예외를 삼키지 않고, 테스트가 성공 수·실패 수를 따로 센다.
     * 시한 안에 안 끝나면 실패다. 교착이 나면 잠금 시한(`D4`)이 끊어 주지만, 그 밖의 무한 대기를 여기서 끊는다.
     */
    protected fun <T> runConcurrently(count: Int, timeout: Long = 60, action: (index: Int) -> T): List<Result<T>> {
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(count)
        try {
            val futures = (0 until count).map { index ->
                pool.submit<Result<T>> {
                    ready.countDown()
                    start.await()
                    runCatching { action(index) }
                }
            }
            check(ready.await(timeout, TimeUnit.SECONDS)) { "스레드 $count 가 시한 안에 출발선에 안 모였다" }
            start.countDown()
            return futures.map { it.get(timeout, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    companion object {
        /** 이 바탕이 만든 행의 표식. 계정 이메일·기획사 코드·공연장 이름에 붙이고, 정리는 이것만 지운다. */
        const val PREFIX = "concurrency-"
    }
}
