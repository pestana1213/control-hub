# ---------- BUILD ----------
FROM maven:3.9.16-eclipse-temurin-25 AS builder

WORKDIR /build

ARG OTEL_JAVA_AGENT_VERSION=2.28.1

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

RUN curl --fail --silent --show-error --location \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVA_AGENT_VERSION}/opentelemetry-javaagent.jar" \
    --output opentelemetry-javaagent.jar


# ---------- RUNTIME ----------
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

COPY --from=builder /build/target/*.jar app.jar
COPY --from=builder /build/opentelemetry-javaagent.jar opentelemetry-javaagent.jar

EXPOSE 8080

ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "/app/app.jar"]
