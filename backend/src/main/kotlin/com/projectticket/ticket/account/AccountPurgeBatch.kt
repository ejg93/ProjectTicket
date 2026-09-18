package com.projectticket.ticket.account

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 유예가 지난 탈퇴 계정의 개인정보를 지우고, 보존 기간이 지난 감사를 지운다(5a, `D9` + 개인정보보호법 제21조).
 *
 * **행을 안 지운다.** 예매·결제·정산이 계정을 외래키로 잡고 있어서 지우면 산 기록이 끌려가고,
 * 남겨야 할 것은 「누가」가 아니라 「무슨 일이 있었나」다. 그래서 이메일·이름·해시만 null 로 만든다 —
 * `account_live_has_identity_check` 가 **살아 있는 계정은 그러지 못하게** 막는다(`V2`).
 *
 * 동의(`account_consent`)는 그대로 둔다. 그 행은 계정을 물리 삭제할 때 cascade 로 따라가고,
 * 지금은 「무엇에 동의했었나」가 분쟁의 증거라 남긴다.
 *
 * 감사는 3년이다. `audit_log` 의 보존 가드 트리거가 그 안쪽 삭제를 막으므로 여기 조건이 곧 그 경계다(`V3`).
 *
 * **이 배치만 `ticket_purge` 역할로 돈다**(4a). 앱이 입는 역할에는 `audit_log` 를 지울 권한이 없다 —
 * 트리거는 표 주인이 끌 수 있어서 권한으로 한 겹 더 내렸다(`D9`).
 */
@Component
class AccountPurgeBatch(private val jdbc: JdbcClient) {

    private val log = LoggerFactory.getLogger(AccountPurgeBatch::class.java)

    /**
     * @return 이번에 파기한 계정 수
     *
     * **감사를 지우는 동안만 역할을 바꾼다**(4a). 앱이 입는 `ticket_app` 에는 그 권한이 없다 —
     * 지울 수 있는 자리를 코드에서 **한 곳으로 좁히는 것**이 이 두 줄의 목적이다. `set local` 이라 트랜잭션이 끝나면 되돌아간다.
     *
     * 풀을 따로 두지 않는다. `DataSource` 빈을 하나 더 만들면 Boot 의 기본 자동설정이 통째로 꺼져서
     * **앱 전체가 그 연결을 쓰게 된다** — 실제로 그렇게 됐다가 되돌렸다(`stack.md`).
     */
    @Scheduled(fixedDelayString = PURGE_INTERVAL)
    @Transactional
    fun purgeDue(): Int {
        // 기준 시각은 DB 다(`D7`). 이미 비운 계정은 `email is not null` 이 걸러서 두 번 돌아도 같은 결과다.
        val purged = jdbc.sql(
            """
            update account
               set email = null, password_hash = null, display_name = null
             where deleted_at < now() - make_interval(days => :days)
               and email is not null
            returning account_id
            """,
        )
            .param("days", GRACE_DAYS)
            .query(Long::class.java)
            .list()
            .size

        jdbc.sql("set local role ticket_purge").update()
        val audits = jdbc.sql("delete from audit_log where created_at < now() - make_interval(years => :years)")
            .param("years", AUDIT_RETENTION_YEARS)
            .update()
        jdbc.sql("set local role ticket_app").update()

        if (purged > 0 || audits > 0) {
            log.info("개인정보 파기 계정={}건 감사={}건", purged, audits)
        }
        return purged
    }

    companion object {
        /** 탈퇴하고 이만큼 지나면 지운다. 되돌려 달라는 요청이 오는 기간이고, 그 뒤로는 남길 이유가 없다 */
        const val GRACE_DAYS = 30

        /** 감사 보존 3년(`D9`). `audit_log` 의 가드 트리거가 드는 것과 같은 값이어야 한다 */
        const val AUDIT_RETENTION_YEARS = 3

        /** 하루 한 번. 파기가 몇 시간 늦는 것은 문제가 안 되고, 자주 돌아도 지우는 총량은 같다 */
        const val PURGE_INTERVAL = "PT24H"
    }
}
