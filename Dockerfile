# ==============================================================================
# Stage 1: Build Stage (Eclipse Temurin 21 JDK on Alpine)
# ==============================================================================
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Install build dependencies
RUN apk add --no-cache bash curl

# Copy maven wrapper and pom.xml first for efficient layer caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x ./mvnw

# Pre-fetch project dependencies
RUN ./mvnw dependency:go-offline -B || true

# Copy source code and build production jar
COPY src/ ./src/
RUN ./mvnw clean package -DskipTests -B

# ==============================================================================
# Stage 2: Production Runtime Stage (Eclipse Temurin 21 JRE on Alpine)
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Security: Create non-root dedicated application service account
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Create writable upload and log volumes with non-root ownership
RUN mkdir -p /app/uploads /app/logs && \
    chown -R appuser:appgroup /app

# Copy built artifact from builder stage
COPY --from=builder --chown=appuser:appgroup /app/target/*.jar app.jar

# Drop privileges to non-root user
USER appuser

# Expose HTTP service port
EXPOSE 8080

# Configure production-tuned JVM memory flags for 512 MB containers (Render / Koyeb)
ENV JAVA_OPTS="-Xmx260m -Xms128m -XX:MaxMetaspaceSize=110m -XX:+UseSerialGC -Xss256k -Djdk.virtualThreadSupport=true -Djava.security.egd=file:/dev/./urandom"

# Healthcheck monitoring Spring Boot Actuator / API Health
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || \
        wget --no-verbose --tries=1 --spider http://localhost:8080/api/health || exit 1

# Launch application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
