package com.projectticket.ticket.notification

import com.projectticket.ticket.SchedulerLock
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
 *
 * **대수만큼 겹치지는 않는다**(`I17-5`). `takePending` 의 `skip locked` 는 반환과 함께 풀려 파드 N 대가 같은 행을 N 번 보냈다 —
 * 스케줄러 입구([sweepDue])가 락을 든다. [sweep] 은 테스트가 직접 부르는 자리라 락을 안 든다. Redis 가 죽으면 락 없이 돌지 않고 건너뛴다 —
 * 그러면 겹침이 돌아온다. 락의 TTL(30초) 안에 배치 하나가 끝난다는 전제다 — 모의 발송은 즉시다. 실물 SMTP 면 TTL 을 배치에 맞춘다.
 */
@Component
class NotificationSweeper(
    private val store: NotificationStore,
    private val sender: MockNotificationSender,
    private val lock: SchedulerLock,
) {

    private val log = LoggerFactory.getLogger(NotificationSweeper::class.java)

    /** @return 보낸 수. 락을 남이 쥐고 있으면 0 */
    @Scheduled(fixedDelayString = SWEEP_INTERVAL)
    fun sweepDue(): Int = lock.runExclusively(LOCK_NAME, onOutage = SchedulerLock.OnOutage.SKIP) { sweep() } ?: 0

    /** @return 보낸 수(실패·건너뜀 제외) */
    fun sweep(): Int {
        val pending = store.takePending(BATCH_SIZE)
        if (pending.isEmpty()) {
            log.debug("알림 발송 — 보낼 것 없음")
            return 0
        }

        var sent = 0
        var failed = 0
        var skipped = 0
        for (row in pending) {
            val email = row.email
            if (email == null) {
                // 탈퇴하면 이메일이 null 이다(`V2` 파기가 비우거나, 그 전이면 `takePending` 이 비운다). 다시 해도 같으니 재시도가 아니라 건너뛴다.
                store.markSkipped(row.notificationId, MockNotificationSender.WITHDRAWN_REASON)
                skipped++
                continue
            }

            val result = sender.send(address = email, subject = row.subject, body = row.body)
            if (result.succeeded) {
                store.markSent(row.notificationId)
                sent++
            } else {
                // 재시도·DLQ 는 29 가 든다.
                val reason = checkNotNull(result.failureReason) { "실패면 사유가 늘 있다 — `Result.failed` 가 그렇게 만든다" }
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

        /** 락 이름. 인스턴스가 여럿이어도 이 이름 하나를 두고 다툰다(`I17-5`) */
        const val LOCK_NAME = "notification-sweeper"

        const val BATCH_SIZE = 100
    }
}
