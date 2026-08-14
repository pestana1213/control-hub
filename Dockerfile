# ---------- BUILD ----------
FROM maven:3.9.16-eclipse-temurin-25 AS builder

WORKDIR /build

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests


# ---------- RUNTIME ----------
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]