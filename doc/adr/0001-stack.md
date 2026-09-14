# ADR 0001 — 스택과 미룬 것

2026-09-14. 사용자 결정.

## 맥락

전자정부 프레임워크(Java·Spring) 경력의 국내 백엔드 지원자가 예매 시스템으로 아키텍처를 익히고 포트폴리오를 만든다.
국내 백엔드 공고의 60~70% 가 Java+Spring 이고 주요 IT 기업이 Kotlin+Spring 을 쓴다. ProjectShop(Spring Boot 4·Next.js)이 이식 원본이다.

## 결정

| 항목 | 결정 | 버린 것 | 이유 |
|---|---|---|---|
| 언어 | **Kotlin + Spring Boot 4** | Java(차별화 없음), Go·NestJS(국내 공고 적고 ProjectShop 재사용 0) | 생태계가 같아 ProjectShop 규칙·코드가 이식되고, 이력서에 새 언어가 붙는다 |
| DB 접근 | **JdbcClient**(ProjectShop 동일) | JPA | 좌석 동시성은 SQL 을 직접 보는 것이 학습에 맞다. 낙관락은 ADR 로 비교만 한다 |
| 좌석 최종 진실 | **PostgreSQL 조건부 UPDATE** | Redis 만으로 확정 | Redis 는 트래픽을 줄이는 자리, 확정은 DB 가 한다 |
| 대기열 | Redis ZSET 대기 + 활성 토큰 TTL. 좌석 선점 5분 | Kafka 대기열 | 순서·입장 제어에 ZSET 이 맞고 참조 구현 전부가 이 방식이다 |
| Kafka 시점 | **소비자가 둘이 되는 청크에서** 들인다. 그전엔 Spring 이벤트 + DB 아웃박스 | 처음부터 Kafka | 소비자 하나면 브로커가 값을 안 한다. 교체 자체가 ADR 한 편이다 |
| 인증 | ProjectShop 세션 방식 포팅 | JWT | 토큰은 대기열 토큰에서 따로 배운다. 겹치지 않게 |
| 결제 | ProjectShop `MockPaymentGateway` 개조 | 실제 PG 샌드박스 | 운영 안 한다. 상태 전이·멱등성은 모의로도 연습된다 |
| 인프라 | Docker Compose → `--scale app=3` + nginx → **kind k8s + Helm** | AWS·EKS | k8s 는 로컬이 무료고 채용에서 kind 냐 EKS 냐를 안 따진다. AWS 는 과금이라 안 한다 |
| 호스팅 | Railway(선택, 맨 끝) | AWS | 웹 호스팅 자체는 스택이 아니다 |
| 부하 | k6. 동시 1만 접속·좌석 1천 경쟁 시나리오 | | 수치가 있어야 측정 지점을 설계한다 |
| 도메인 | 공연(콘서트·뮤지컬) 한 종류. 기획사 여럿. 좌석 등급+구역+지정석 | 스포츠·영화 | 종류를 늘리면 스키마가 갈린다 |
| 저장소 | 새 GitHub repo, 모노레포(backend·frontend), public | | 포트폴리오 |

## 결과

- Kafka·k8s·Railway 는 분할표에 행이 있되 뒤에 선다. 앞당기지 않는다
- ProjectShop 의 Java 모듈은 복사가 아니라 **Kotlin 포팅**이다 — 복사하면 Kotlin 학습이 0 이다
