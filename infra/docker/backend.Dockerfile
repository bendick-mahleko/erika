# syntax=docker/dockerfile:1
# ERIKA back end (API and worker share this image; the Spring profile selects the role).
# Build context: repository root (the build needs api/openapi.yaml).

FROM eclipse-temurin:24-jdk AS build
WORKDIR /src
COPY api/ api/
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts backend/
COPY backend/gradle/ backend/gradle/
WORKDIR /src/backend
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies -q > /dev/null
COPY backend/src/ src/
ARG ERIKA_VERSION=dev
ENV ERIKA_VERSION=${ERIKA_VERSION}
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test \
 && java -Djarmode=tools -jar build/libs/*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:24-jre AS runtime
RUN groupadd --system erika && useradd --system --gid erika --home /app erika
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
ARG ERIKA_VERSION=dev
ENV ERIKA_VERSION=${ERIKA_VERSION} \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
USER erika
EXPOSE 8080 8081
# SPRING_PROFILES_ACTIVE must be set to "api" or "worker" (plus an environment profile).
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
