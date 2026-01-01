# Aether Node Container Image
# Distributed runtime node for the Aether cluster

FROM eclipse-temurin:25-alpine

LABEL org.opencontainers.image.title="Aether Node"
LABEL org.opencontainers.image.description="Aether distributed runtime node"
LABEL org.opencontainers.image.version="0.6.4"
LABEL org.opencontainers.image.source="https://github.com/siy/aether"

# Create non-root user for security
RUN addgroup -g 1000 aether && \
    adduser -u 1000 -G aether -s /bin/sh -D aether

# Create directories for app, data, and config
RUN mkdir -p /app /data /config && \
    chown -R aether:aether /app /data /config

WORKDIR /app

# Copy the shaded JAR from build context
COPY --chown=aether:aether node/target/aether-node.jar /app/aether-node.jar

# Environment variables for configuration
# These map to Main.java command-line arguments
ENV NODE_ID=""
ENV CLUSTER_PORT="8090"
ENV MANAGEMENT_PORT="8080"
ENV PEERS=""
ENV JAVA_OPTS="-Xmx512m -XX:+UseZGC -XX:+ZGenerational"

# Expose ports
# 8090 - Cluster communication (consensus, inter-node messaging)
# 8080 - Management API (REST, WebSocket dashboard)
EXPOSE 8090 8080

# Health check using /health endpoint
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:${MANAGEMENT_PORT}/health || exit 1

# Switch to non-root user
USER aether

# Entrypoint builds command from environment variables
ENTRYPOINT ["sh", "-c", "\
    java $JAVA_OPTS -jar /app/aether-node.jar \
    ${NODE_ID:+--node-id=$NODE_ID} \
    --port=$CLUSTER_PORT \
    ${PEERS:+--peers=$PEERS} \
"]
