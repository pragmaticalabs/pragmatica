# Aether Forge - Docker Image
# Requires Java 25+ (pragmatica-lite dependency)
# #3806 (CVE-2026-114938, Java4Shell): the base is pinned to a Temurin build at or above JDK_FLOOR, and the build FAILS if the JDK
# inside is below it (an unreadable version fails too). JDK_FLOOR is asserted equal to JvmVulnerabilityGate.JDK_FLOOR by
# JdkFloorPinnedTest; override JDK_BASE only to take a newer JDK.
ARG JDK_BASE=eclipse-temurin:25.0.5.1_2-jre-alpine
ARG JDK_FLOOR=25.0.5.1

FROM ${JDK_BASE}

ARG JDK_FLOOR
RUN v="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.version = //p')"; [ -n "$v" ] && [ "$(printf '%s\n%s\n' "$JDK_FLOOR" "$v" | sort -V | head -n1)" = "$JDK_FLOOR" ] || { echo "JDK ${v:-unknown} is below $JDK_FLOOR (CVE-2026-114938, Java4Shell)" >&2; exit 1; }

LABEL maintainer="Aether Team"
LABEL description="Aether Forge - Standalone simulator CLI for testing cluster resilience"

# Create app directory
WORKDIR /app

# Copy the shaded JAR
COPY target/forge-*-shaded.jar /app/aether-forge.jar

# Expose ports
# 8888 - Forge HTTP API server
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
    CMD wget -q --spider http://localhost:${FORGE_PORT}/api/nodes/status || exit 1

# Run the Forge server
ENTRYPOINT ["sh", "-c", "java -XX:+ExitOnOutOfMemoryError $JAVA_OPTS -jar /app/aether-forge.jar"]
