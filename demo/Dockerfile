# Aether Resilience Demo - Docker Image
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Aether Team"
LABEL description="Aether Resilience Demo - C-Level presentation dashboard"

# Create app directory
WORKDIR /app

# Copy the shaded JAR
COPY target/demo-*-shaded.jar /app/aether-demo.jar

# Expose ports
# 8888 - Dashboard HTTP server
# 5050-5060 - Cluster node ports
EXPOSE 8888
EXPOSE 5050-5060

# Environment variables with defaults
ENV DEMO_PORT=8888
ENV CLUSTER_SIZE=5
ENV LOAD_RATE=1000

# JVM options for container environment
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"

# Health check
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -q --spider http://localhost:${DEMO_PORT}/api/status || exit 1

# Run the demo server
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/aether-demo.jar"]
