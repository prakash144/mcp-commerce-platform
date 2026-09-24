# syntax=docker/dockerfile:1
# Multi-stage: Maven build → minimal Java 21 JRE runtime, runs as non-root user.
# Build context is the REPO ROOT (docker-compose sets context: ..): the
# avro-maven-plugin compiles common/events/avro, which lives outside
# order-service/, so this service cannot be built from its own subdirectory.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Layer 1 — resolve dependencies (cached unless the pom or schemas change).
COPY order-service/pom.xml /workspace/order-service/pom.xml
COPY common/events/avro /workspace/common/events/avro
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -DskipTests -f /workspace/order-service/pom.xml dependency:go-offline || true

# Layer 2 — sources + package (no -q so build failures surface in logs).
COPY order-service/src /workspace/order-service/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -DskipTests -f /workspace/order-service/pom.xml package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/order-service/target/*.jar app.jar
USER app
EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]