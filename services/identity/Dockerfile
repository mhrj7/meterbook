# syntax=docker/dockerfile:1.17
# Identity service image: built from the locked Gradle build, run on distroless Java 25 as a non-root user.

FROM mirror.gcr.io/library/eclipse-temurin:25-jdk-noble@sha256:ba0f0e1a33864105e1d454e88105c617ab40f71062db1f306bf58be32a3f8e5e AS build
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

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:5d3177252775d4b01603470fe21c015393019089904f68724bd37242e4bc6e6d
WORKDIR /app
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER 65532:65532
EXPOSE 8080 9090
# Container-aware heap sizing; the read-only root filesystem only needs /tmp (an emptyDir in the chart).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "org.springframework.boot.loader.launch.JarLauncher"]
