# syntax=docker/dockerfile:1.17
# Identity service image: built from the locked Gradle build, run on distroless Java 25 as a non-root user.

FROM mirror.gcr.io/library/eclipse-temurin:25-jdk-noble@sha256:7247bd8d2aeec64efe908dc72396d88101417d24ce3642703728382d96afb965 AS build
WORKDIR /src
ENV GRADLE_USER_HOME=/cache/gradle
# Dependencies first, so source changes reuse the dependency layer.
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts gradle.lockfile ./
COPY gradle gradle
RUN --mount=type=cache,target=/cache/gradle ./gradlew --no-daemon --quiet dependencies > /dev/null
COPY src src
# Tests run in CI before the image is built; here only the jar is produced.
RUN --mount=type=cache,target=/cache/gradle ./gradlew --no-daemon --quiet bootJar \
 && java -Djarmode=tools -jar build/libs/identity.jar extract --layers --launcher --destination /layers

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:fc6f4aec634ee433ff16a5d18814181e7928f4be9f434b44b48231e2eb7becee
WORKDIR /app
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER 65532:65532
EXPOSE 8080 9090
# Container-aware heap sizing; the read-only root filesystem only needs /tmp (an emptyDir in the chart).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "org.springframework.boot.loader.launch.JarLauncher"]
