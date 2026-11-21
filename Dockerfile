# syntax=docker/dockerfile:1

# ---------- Build stage ----------
FROM docker.io/library/eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Resolve the Gradle distribution and dependencies first so they are cached between source changes.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

COPY src src
RUN ./gradlew --no-daemon clean bootJar -x test \
    && java -Djarmode=tools -jar build/libs/*.jar extract --layers --launcher --destination /workspace/extracted

# ---------- Runtime stage ----------
FROM docker.io/library/eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="hackathon-ai-agent" \
      org.opencontainers.image.description="CI/CD Reliability AI Agent - Tekton monitoring backend" \
      org.opencontainers.image.source="https://github.com/rafik790/hackathon-ai-agent"

RUN addgroup -S -g 10001 agent && adduser -S -u 10001 -G agent -h /app agent
WORKDIR /app

# Layered copy: dependencies change rarely, application classes often.
COPY --from=build --chown=agent:agent /workspace/extracted/dependencies/ ./
COPY --from=build --chown=agent:agent /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=agent:agent /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=agent:agent /workspace/extracted/application/ ./

USER 10001:10001

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom" \
    SERVER_PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/liveness || exit 1

# Exec form: the JVM is PID 1 and receives SIGTERM directly, triggering Spring's graceful shutdown.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
