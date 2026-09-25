package com.projectticket.ticket.notification

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 안 보낸 알림을 보낸다(사용자 선택 — 리스너는 행만 만들고 발송은 여기다).
 *
 * **바깥 호출이 트랜잭션 밖이다**(`D4` 「트랜잭션 경계」). 모의 발송은 즉시 답하지만 실물 SMTP 는 느리고,
 * 그것을 소비 트랜잭션에 넣으면 메일 하나가 릴레이 한 회를 붙잡는다. 트랜잭션은 [NotificationStore] 에 있다 —
 * 같은 클래스에 두면 자기 호출이라 프록시를 안 지난다(`stack.md`).
 *
 * **받는 주소를 보낼 때 읽는다**(`D11`). 행을 만들 때 굳히면 그 사이에 바뀐 주소로 안 가고, 탈퇴한 계정에도 나간다.
 *
 * 발송과 표시 사이에 남이 같은 행을 집어 가면 **같은 메일이 두 번 나갈 수 있다**(at-least-once). 돈이 안 움직이는 자리라 그 대가를 받아들인다 —
 * 한 번만 보내려면 보내기 전에 `sending` 으로 옮겨야 하고, 그러면 그 상태에서 죽은 행을 되살리는 자리가 또 필요하다.
 */
@Component
class NotificationSweeper(private val store: NotificationStore, private val sender: MockNotificationSender) {

    private val log = LoggerFactory.getLogger(NotificationSweeper::class.java)

    /** @return 보낸 수(실패·건너뜀 제외) */
    @Scheduled(fixedDelayString = SWEEP_INTERVAL)
    fun sweep(): Int {
        val pending = store.takePending(BATCH_SIZE)
        if (pending.isEmpty()) {
            log.debug("알림 발송 — 보낼 것 없음")
            return 0
        }

        var sent = 0
        var failed = 0
        var skipped = 0
        pending.forEach { row ->
            val email = row.email
            if (email == null) {
                // 탈퇴하면 이메일이 null 이다(`V2` — 파기가 컬럼을 비운다). 다시 해도 같으니 재시도가 아니라 건너뛴다.
                store.markSkipped(row.notificationId, MockNotificationSender.WITHDRAWN_REASON)
                skipped++
                return@forEach
            }

            val result = sender.send(email, row.subject, row.body)
            if (result.succeeded) {
                store.markSent(row.notificationId)
                sent++
            } else {
                // 재시도·DLQ 는 29 가 든다.
                val reason = requireNotNull(result.failureReason) { "실패면 사유가 늘 있다 — `Result.failed` 가 그렇게 만든다" }
                store.markFailed(row.notificationId, reason)
                failed++
            }
        }

        log.info("알림 발송 성공={}건 실패={}건 건너뜀={}건", sent, failed, skipped)
        return sent
    }

    companion object {
        /** 사람이 기다리는 자리라 짧다. 스윕 셋(선점 30초·릴레이 1초·알림 5초) 중 가운데다 */
        const val SWEEP_INTERVAL = "PT5S"

        const val BATCH_SIZE = 100
    }
}
