package com.projectticket.ticket.idempotency

import com.projectticket.ticket.error.ErrorCode
import com.projectticket.ticket.error.TicketException
import org.springframework.dao.CannotAcquireLockException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.util.HexFormat

/**
 * 같은 요청이 두 번 도착해도 결과가 하나이게 만든다(`D4` 「멱등키」). ProjectShop `IdempotencyService` 를 Kotlin 으로 옮겼다.
 *
 * **키 선점·처리·응답 저장이 한 트랜잭션이다.** 필터에 두면 자기 트랜잭션을 따로 열어야 하고, 그러면
 * 「예매는 커밋됐는데 키 기록은 진행중」인 구간이 생긴다 — 그 구간에서 죽으면 재전송이 영영 409 다. 여기서는 어디서 죽든 전부 롤백이다.
 *
 * **실패는 저장하지 않는다.** 예외가 나면 이 행도 같이 롤백된다. 실패는 자원이 안 생기므로 막을 중복이 없고,
 * 재전송은 같은 이유로 같은 실패를 받는다.
 *
 * 상태 코드를 안 저장한다 — 성공만 저장하고 입구마다 성공 코드가 하나라 입구가 안다.
 */
@Service
class IdempotencyService(private val jdbc: JdbcClient, private val json: ObjectMapper) {

    /**
     * 이 키로 처리한 적이 없으면 [work] 를 돌리고, 있으면 그때 응답을 그대로 돌려준다.
     * [work] 안의 서비스가 `@Transactional` 이면 새 트랜잭션을 안 열고 이 트랜잭션에 참여한다(`REQUIRED`).
     *
     * @param request 본문 비교용. 같은 키로 다른 본문이 오면 422
     * @param responseType 재생할 때 되돌릴 타입. 저장이 JSON 이라 필요하다
     */
    @Transactional
    fun <T : Any> run(accountId: Long, key: String, request: Any, responseType: Class<T>, work: () -> T): T {
        val hash = sha256(json.writeValueAsString(request))

        if (!claim(accountId, key, hash)) {
            return replay(accountId, key, hash, responseType)
        }

        val result = work()
        jdbc.sql("update idempotency_key set response_body = :body::jsonb where account_id = :account and key_value = :key")
            .param("body", json.writeValueAsString(result))
            .param("account", accountId)
            .param("key", key)
            .update()
        return result
    }

    /**
     * 이 키를 내가 처음 잡았나. **insert 가 곧 락이다** — 조회해서 검사하고 넣는 절차가 없어서 그 사이에 끼어들 틈이 없다.
     *
     * 같은 키의 뒤 요청은 앞이 끝날 때까지 유일 인덱스에서 기다린다. 앞이 커밋되면 재생을 읽고, 롤백되면 자기가 처리한다 —
     * 대기가 알아서 옳은 답을 낸다. 다만 앞이 길면 뒤가 붙잡히므로 `lock_timeout` 으로 끊고 409 를 준다(`D4` 2초).
     */
    private fun claim(accountId: Long, key: String, hash: String): Boolean {
        // SET 은 값 바인딩이 안 되는 자리다. 상수라 이어 붙여도 되고, 들어갈 값을 우리가 정한다.
        jdbc.sql("set local lock_timeout = $LOCK_TIMEOUT_MS").update()
        return try {
            jdbc.sql(
                """
                insert into idempotency_key (account_id, key_value, request_hash)
                values (:account, :key, :hash)
                on conflict (account_id, key_value) do nothing
                """,
            )
                .param("account", accountId)
                .param("key", key)
                .param("hash", hash)
                .update() == 1
        } catch (e: CannotAcquireLockException) {
            // 앞 요청이 아직 커밋을 안 했다. 진행 중이라는 뜻이지만 그 행은 우리에게 안 보인다.
            throw TicketException(ErrorCode.IDEMPOTENCY_IN_PROGRESS)
        }
    }

    private fun <T : Any> replay(accountId: Long, key: String, hash: String, responseType: Class<T>): T {
        val stored = jdbc.sql(
            "select request_hash, response_body::text as response_body from idempotency_key where account_id = :account and key_value = :key",
        )
            .param("account", accountId)
            .param("key", key)
            .query(Stored::class.java)
            .optional()
            .orElseThrow { IllegalStateException("선점에 실패했는데 행이 없다: $key") }

        if (stored.requestHash != hash) {
            throw TicketException(ErrorCode.IDEMPOTENCY_KEY_REUSED)
        }
        // 커밋된 행에는 반드시 응답이 있다 — `idempotency_key_response_check` 가 커밋 때 본다(`V7`). 없으면 그 트리거가 빠진 것이다.
        return json.readValue(stored.responseBody ?: throw IllegalStateException("저장된 멱등 응답이 비었다: $key"), responseType)
    }

    data class Stored(val requestHash: String, val responseBody: String?)

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    companion object {
        /** `D4` — 앞 요청을 기다리는 한도 2초 */
        const val LOCK_TIMEOUT_MS = 2_000
    }
}
