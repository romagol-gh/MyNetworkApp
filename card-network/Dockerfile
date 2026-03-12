# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /app
COPY pom.xml .
# Download dependencies first (layer-cached unless pom.xml changes)
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app
COPY --from=build /app/target/card-network-*.jar app.jar

# 8080 = HTTP admin dashboard + REST API
# 8583 = ISO 8583 TCP gateway
EXPOSE 8080 8583

USER appuser

ENTRYPOINT ["java", "-jar", "app.jar"]
