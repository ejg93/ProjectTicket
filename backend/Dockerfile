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

# root 로 안 돌린다(`D9`). 컨테이너가 뚫려도 호스트 볼륨을 함부로 못 쓴다.
# 로그 디렉터리를 미리 만들어 준다 — logback 이 `logs/ticket.log` 를 여는데 `/app` 은 root 것이라 그냥 두면 기동이 죽는다.
RUN useradd --system --create-home --uid 10001 ticket \
 && mkdir -p /app/logs \
 && chown -R ticket /app
USER ticket

COPY --from=build /src/build/libs/*.jar app.jar

EXPOSE 8080
# 컨테이너가 받은 메모리의 일부만 힙으로 쓴다. 안 적으면 기본값이 호스트 전체를 보고 정해진다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
