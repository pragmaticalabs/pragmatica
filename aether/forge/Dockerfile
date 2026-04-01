# Aether Forge - Docker Image
# Requires Java 25+ (pragmatica-lite dependency)
FROM eclipse-temurin:25-jre-alpine

LABEL maintainer="Aether Team"
LABEL description="Aether Forge - Standalone simulator CLI for testing cluster resilience"

# Create app directory
WORKDIR /app

# Copy the shaded JAR
COPY target/forge-*-shaded.jar /app/aether-forge.jar

# Expose ports
# 8888 - Dashboard HTTP server
# 5050-5060 - Cluster node ports
EXPOSE 8888
EXPOSE 5050-5060

# Environment variables with defaults
ENV FORGE_PORT=8888
ENV CLUSTER_SIZE=5
ENV LOAD_RATE=1000

# JVM options for container environment
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"

# Health check
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -q --spider http://localhost:${FORGE_PORT}/api/status || exit 1

# Run the Forge server
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/aether-forge.jar"]
