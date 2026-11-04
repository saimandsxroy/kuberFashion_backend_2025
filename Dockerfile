# ==============================================================================
# Stage 1: Build Stage (Official Maven 3.9 on Eclipse Temurin 21 Alpine)
# Pre-packaged Maven prevents "429 Too Many Requests" on Render/CI builders
# ==============================================================================
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Copy pom.xml first for efficient Docker layer caching
COPY pom.xml ./

# Pre-fetch project dependencies
RUN mvn dependency:go-offline -B || true

# Copy source code and build production jar
COPY src/ ./src/
RUN mvn clean package -DskipTests -B

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
# -Xmx200m: caps heap at 200MB (leaves plenty of headroom for Metaspace and OS)
# -XX:MaxMetaspaceSize=180m: guarantees Spring Boot 3 + Hibernate never hits Metaspace OOM
ENV JAVA_OPTS="-Xmx200m -Xms64m -XX:MetaspaceSize=96m -XX:MaxMetaspaceSize=180m -XX:+UseSerialGC -Xss256k -Djdk.virtualThreadSupport=true -Djava.security.egd=file:/dev/./urandom"

# Healthcheck monitoring Spring Boot Root / Health
HEALTHCHECK --interval=20s --timeout=3s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/ || \
        wget --no-verbose --tries=1 --spider http://localhost:8080/healthz || exit 1

# Launch application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
