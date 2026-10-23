# 두 단계다. 빌드 단계의 JDK·Gradle 캐시가 실행 이미지에 따라오면 이미지가 몇 배로 커진다.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# 래퍼와 빌드 파일을 먼저 넣고 의존성을 받는다 — 소스만 고친 다음 빌드는 이 층을 다시 안 탄다.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:25-jre
WORKDIR /app

# healthcheck 가 쓸 도구. **JRE 이미지에는 `wget` 도 `curl` 도 없다** — 33 에서 그것을 모르고 `wget` 을 적었다가
# 컨테이너가 계속 unhealthy 였다(앱은 멀쩡했다). 35 에서 드러나 여기서 고친다.
RUN apt-get update \
 && apt-get install --no-install-recommends -y curl \
 && rm -rf /var/lib/apt/lists/*

# root 로 안 돌린다(`D9`). 컨테이너가 뚫려도 호스트 볼륨을 함부로 못 쓴다.
# 로그 디렉터리를 미리 만들어 준다 — logback 이 `logs/ticket.log` 를 여는데 `/app` 은 root 것이라 그냥 두면 기동이 죽는다.
RUN useradd --system --create-home --uid 10001 ticket \
 && mkdir -p /app/logs \
 && chown -R ticket /app
USER ticket

COPY --from=build /src/build/libs/*.jar app.jar

# CDS 아카이브를 이미지 안에서 만든다(53b) — 파드는 일회용이라 실행 중에 만든 아카이브는 다음 파드에 안 남는다.
# 학습 실행은 컨텍스트 refresh 뒤 바로 끝나고(`spring.context.exit=onRefresh`) DB·Redis·Kafka 없이 떠야 한다 — 기동 때 붙는 다섯(Flyway·Hikari·Kafka 소비자·Kafka 토픽 생성·Spring Session 의 Redis 설정)을 끈다.
# 둘째 실행이 확인이다 — `-Xshare:on` 은 기본 아카이브를 못 쓰면 서지만, 동적(top) 아카이브는 못 써도 경고만 내고 갈 수 있어서
# 클래스가 top 아카이브에서 올라왔는지 로그로 보고 0 이면 빌드를 세운다. 실행은 기본 `-Xshare:auto` 라 아카이브가 깨져도 뜬다.
# 아카이브는 같은 JDK·같은 클래스패스에서만 맞는다 — 경로 `application/app.jar` 를 ENTRYPOINT 와 같게 둔다.
ARG CDS_TRAIN="-Dspring.context.exit=onRefresh -Dspring.flyway.enabled=false -Dspring.datasource.hikari.initialization-fail-timeout=-1 -Dspring.kafka.listener.auto-startup=false -Dspring.kafka.admin.auto-create=false -Dspring.session.data.redis.configure-action=none"
RUN java -Djarmode=tools -jar app.jar extract --destination application \
 && java -XX:ArchiveClassesAtExit=application/app.jsa $CDS_TRAIN -jar application/app.jar \
 && java -Xshare:on -XX:SharedArchiveFile=application/app.jsa -Xlog:class+load=info:file=/tmp/cds.log $CDS_TRAIN -jar application/app.jar \
 && { grep -q 'source: shared objects file (top)' /tmp/cds.log || { echo 'CDS: top 아카이브에서 올라온 클래스가 없다' >&2; exit 1; }; } \
 && rm -rf app.jar logs/* /tmp/cds.log /tmp/tomcat*

EXPOSE 8080
# 컨테이너가 받은 메모리의 일부만 힙으로 쓴다. 안 적으면 기본값이 호스트 전체를 보고 정해진다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -XX:SharedArchiveFile=application/app.jsa -jar application/app.jar"]
