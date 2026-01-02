# Aether Forge Container Image
# Cluster testing simulator with visual dashboard

FROM eclipse-temurin:25-alpine

LABEL org.opencontainers.image.title="Aether Forge"
LABEL org.opencontainers.image.description="Aether cluster testing simulator with visual dashboard"
LABEL org.opencontainers.image.version="0.7.0"
LABEL org.opencontainers.image.source="https://github.com/siy/aether"

# Create non-root user for security
RUN addgroup -g 1000 aether && \
    adduser -u 1000 -G aether -s /bin/sh -D aether

# Create directories
RUN mkdir -p /app && \
    chown -R aether:aether /app

WORKDIR /app

# Copy the shaded JAR from build context
COPY --chown=aether:aether forge/target/aether-forge.jar /app/aether-forge.jar

# Environment variables matching ForgeServer.java
ENV FORGE_PORT="8888"
ENV CLUSTER_SIZE="5"
ENV LOAD_RATE="1000"
ENV JAVA_OPTS="-Xmx1g -XX:+UseZGC -XX:+ZGenerational"

# Dashboard port
EXPOSE 8888

# Health check against Forge API
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:${FORGE_PORT}/api/metrics || exit 1

# Switch to non-root user
USER aether

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/aether-forge.jar"]
