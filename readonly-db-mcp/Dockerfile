FROM gradle:8.14.3-jdk17 AS builder

WORKDIR /workspace

COPY gradle gradle
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY settings.gradle settings.gradle
COPY build.gradle build.gradle
COPY src src

RUN bash ./gradlew clean test bootJar --no-daemon

FROM eclipse-temurin:17-jre-jammy

RUN groupadd --system app \
    && useradd --system --gid app --home-dir /app app

WORKDIR /app

COPY --from=builder \
    /workspace/build/libs/readonly-db-mcp-0.1.0.jar \
    /app/readonly-db-mcp.jar

RUN mkdir -p /app/logs \
    && chown -R app:app /app

USER app

EXPOSE 9350

ENTRYPOINT ["java", "-jar", "/app/readonly-db-mcp.jar", "--spring.profiles.active=http"]
