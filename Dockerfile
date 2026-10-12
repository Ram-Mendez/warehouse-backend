# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
# Testcontainers needs a Docker daemon: CI runs clean verify before this image build.
# This stage packages only; docker build alone is not a test gate.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
# The runtime contains no compiler, Maven cache or credentials and needs no root privileges.
RUN groupadd --gid 10001 warehouse && useradd --uid 10001 --gid warehouse --no-create-home warehouse
COPY --from=build --chown=warehouse:warehouse /workspace/target/warehouse-backend-*.jar /app/application.jar
USER warehouse:warehouse
EXPOSE 8080
# Exec form passes SIGTERM to Java so Spring can drain active requests.
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
