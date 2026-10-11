-- 개인정보 수집·이용 동의 판 2(개인정보 보호법 제15조 제2항, 청크 39-3c).
--
-- 판 1(V3)의 수집 항목은 「동의 시점의 접속 IP」만 적었다. 코드는 로그인 성공·실패와 탈퇴 실패에도
-- `audit_log.detail.ip` 를 남기고 3년 보존한다(`AccountPurgeBatch.AUDIT_RETENTION_YEARS`) — 고지에 없는 항목을 받는 것이 된다.
-- 처리방침(V21)은 이미 사실대로 적었다. 이 판이 동의 고지를 그것에 맞춘다.
--
-- 새 판을 넣고 옛 판은 남긴다 — `currentItem` 이 `effective_at desc, version desc` 로 고르므로 이 시각부터 가입이 판 2 를 읽고,
-- 이미 받은 동의는 `account_consent.consent_item_id` 가 판 1 을 가리킨 채 남는다. 재동의는 받지 않는다(사용자 결정, 2026-09-26).
insert into consent_item (code, version, title, is_required, sort_no,
                          purpose, collected_items, retention_period, refusal_disadvantage) values
    ('privacy_collect', 2, '개인정보 수집·이용', true, 20,
     '회원 식별과 예매 처리, 공연 변경·취소 안내',
     '이메일, 이름, 비밀번호(암호화 저장), 동의·로그인·탈퇴 요청 시점의 접속 IP',
     '탈퇴 후 30일까지. 로그인·탈퇴 기록(접속 IP 포함)은 3년. 법령이 보존을 요구하는 거래기록은 해당 기간 동안 분리 보관',
     '거부할 수 있으나 회원 가입이 되지 않습니다');
