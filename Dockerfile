# =============================================================================
# MULTI-STAGE DOCKERFILE FOR QUARKUS LAMBDA (JVM & NATIVE)
# =============================================================================
# Build: docker build -t alertas-civil:latest .
# Build Native: docker build --target native -t alertas-civil:native .
# Run Local: docker run -p 8080:8080 -e QUARKUS_PROFILE=dev alertas-civil:latest

# ─── STAGE 1: Build with Maven ────────────────────────────────────────────────
FROM maven:3.9.8-eclipse-temurin-25 AS builder

WORKDIR /build

# Copy pom.xml first for dependency caching
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

# Copy source and build
COPY src ./src
RUN mvn package -DskipTests -q

# ─── STAGE 2: Native Build (Optional Profile) ─────────────────────────────────
FROM ghcr.io/graalvm/native-image-community:23.1.2-java25 AS native-builder

WORKDIR /build

# Install native-image
RUN gu install native-image

# Copy from builder to reuse deps
COPY --from=builder /build /build

# Build native image (takes 5-10 min)
RUN mvn package -Pnative -DskipTests -q

# ─── STAGE 3: JVM Runtime (Fast Startup) ──────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine AS jvm-runtime

WORKDIR /app

# Copy Quarkus fast-jar output
COPY --from=builder /build/target/quarkus-app /app

# Non-root user
RUN addgroup -g 1000 -S appgroup && \
    adduser -u 1000 -S appuser -G appgroup && \
    chown -R appuser:appgroup /app
USER appuser

# Health check endpoint
EXPOSE 8080

# JVM tuning for Lambda/Container
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=50 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"

ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]

# ─── STAGE 4: Native Runtime (Ultra-Fast Cold Start) ──────────────────────────
FROM alpine:3.20 AS native-runtime

WORKDIR /app

# Copy native binary
COPY --from=native-builder /build/target/*-runner /app/application

# Non-root user
RUN addgroup -g 1000 -S appgroup && \
    adduser -u 1000 -S appuser -G appgroup && \
    chown -R appuser:appgroup /app
USER appuser

# Binary is self-contained, no JVM needed
EXPOSE 8080

ENTRYPOINT ["./application"]