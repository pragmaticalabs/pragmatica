# Aether Passive Load Balancer
# Lightweight cluster-aware HTTP load balancer — no consensus overhead

FROM eclipse-temurin:25-noble

LABEL org.opencontainers.image.title="Aether Passive LB"
LABEL org.opencontainers.image.description="Aether passive load balancer node"
ARG VERSION=dev
LABEL org.opencontainers.image.version="${VERSION}"

# Rename existing ubuntu user (uid 1000) to aether
RUN usermod -l aether -d /home/aether -m ubuntu && \
    groupmod -n aether ubuntu

RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends wget && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /app && chown -R aether:aether /app

WORKDIR /app

ARG JAR_PATH=lb/target/aether-lb.jar
COPY --chown=aether:aether ${JAR_PATH} /app/aether-lb.jar

ENV HTTP_PORT="8080"
ENV CLUSTER_PORT="7000"
ENV PEERS=""
ENV JAVA_OPTS="-Xmx256m -XX:+UseZGC -XX:+ZGenerational -XX:+UseCompactObjectHeaders"

EXPOSE 8080

HEALTHCHECK --interval=5s --timeout=3s --start-period=15s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:${HTTP_PORT}/health || exit 1

USER aether

ENTRYPOINT ["sh", "-c", "\
    java $JAVA_OPTS \
    -jar /app/aether-lb.jar \
    --http-port=$HTTP_PORT \
    --cluster-port=$CLUSTER_PORT \
    ${PEERS:+--peers=$PEERS} \
"]
