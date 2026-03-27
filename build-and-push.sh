#!/bin/bash
# Build Aether and push Docker image to target machine for integration testing.
#
# Usage:
#   ./build-and-push.sh                    # Build + push to TARGET_HOST
#   ./build-and-push.sh --skip-build       # Push only (JAR already built)
#   ./build-and-push.sh --push-only        # Same as --skip-build
#
# Environment:
#   TARGET_HOST       — IP/hostname of target machine (required)
#   AETHER_SSH_USER   — SSH user (default: aether)
#   AETHER_SSH_KEY    — SSH key path (default: ~/.ssh/aether_test)
#   AETHER_VERSION    — Version tag (default: from pom.xml)
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Defaults
SSH_USER="${AETHER_SSH_USER:-aether}"
SSH_KEY="${AETHER_SSH_KEY:-$HOME/.ssh/aether_test}"
VERSION="${AETHER_VERSION:-$(grep -m1 '<version>' pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/')}"
IMAGE="ghcr.io/pragmaticalabs/aether-node:${VERSION}"
SKIP_BUILD=false

for arg in "$@"; do
    case "$arg" in
        --skip-build|--push-only) SKIP_BUILD=true ;;
    esac
done

SSH_OPTS="-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o LogLevel=ERROR"

remote() {
    ssh $SSH_OPTS -i "$SSH_KEY" "${SSH_USER}@${TARGET_HOST}" "$@"
}

# Validate
if [ -z "$TARGET_HOST" ]; then
    echo "ERROR: TARGET_HOST not set"
    echo "Usage: TARGET_HOST=192.168.1.100 ./build-and-push.sh"
    exit 1
fi

echo "=== Aether Build & Push ==="
echo "  Version:  ${VERSION}"
echo "  Target:   ${SSH_USER}@${TARGET_HOST}"
echo "  Image:    ${IMAGE}"
echo ""

# Step 1: Build
if [ "$SKIP_BUILD" = false ]; then
    echo "[1/4] Building project (full build with lint + tests)..."
    START=$(date +%s)
    ./build.sh
    END=$(date +%s)
    echo "  Built in $((END - START))s"
else
    echo "[1/4] Skipping build (--skip-build)"
fi

# Verify JAR exists
JAR="aether/node/target/aether-node.jar"
if [ ! -f "$JAR" ]; then
    echo "ERROR: $JAR not found. Run without --skip-build first."
    exit 1
fi
JAR_SIZE=$(du -h "$JAR" | cut -f1)
echo "  JAR: ${JAR} (${JAR_SIZE})"

# Step 2: Sync JAR + Dockerfile to target (rsync for speed)
echo "[2/4] Syncing to target..."
START=$(date +%s)

# Create remote build directory
remote "mkdir -p /tmp/aether-build"

# rsync JAR (only transfers diff — fast on subsequent runs)
rsync -az -e "ssh $SSH_OPTS -i $SSH_KEY" \
    "$JAR" \
    "${SSH_USER}@${TARGET_HOST}:/tmp/aether-build/aether-node.jar"

# rsync Dockerfile + config
rsync -az -e "ssh $SSH_OPTS -i $SSH_KEY" \
    "aether/docker/aether-node/Dockerfile" \
    "aether/docker/aether-node/aether.toml" \
    "${SSH_USER}@${TARGET_HOST}:/tmp/aether-build/"

END=$(date +%s)
echo "  Synced in $((END - START))s"

# Step 3: Build Docker image ON target (uses cached layers)
echo "[3/4] Building Docker image on target..."
START=$(date +%s)

remote << REMOTE_BUILD
cd /tmp/aether-build
# Create Dockerfile only if missing (preserves Docker layer cache)
if [ ! -f Dockerfile.local ]; then
cat > Dockerfile.local << 'DFILE'
FROM eclipse-temurin:25-alpine

RUN addgroup -g 1000 aether && adduser -D -u 1000 -G aether aether
RUN mkdir -p /app /data /config && chown -R aether:aether /app /data /config

# Config changes rarely — cached layer
COPY --chown=aether:aether aether.toml /app/aether.toml

USER aether
WORKDIR /app
EXPOSE 8090/udp 8190/udp 8080

# JAR changes often — last layer for optimal caching
COPY --chown=aether:aether aether-node.jar /app/aether-node.jar

ENTRYPOINT ["sh", "-c", "exec java \${JAVA_OPTS:--Xmx512m -XX:+UseZGC -XX:+ZGenerational} -Djdk.virtualThreadScheduler.parallelism=\${VTHREAD_PARALLELISM:-\$(nproc)} -jar /app/aether-node.jar --config=\${CONFIG_PATH:-/app/aether.toml} --node-id=\${NODE_ID:-node-1} --port=\${CLUSTER_PORT:-8090} --management-port=\${MANAGEMENT_PORT:-8080} \${PEERS:+--peers=\$PEERS}"]
DFILE
echo "  Dockerfile created"
fi

docker build -t ${IMAGE} -f Dockerfile.local . 2>&1 | tail -3
REMOTE_BUILD

END=$(date +%s)
echo "  Built image in $((END - START))s"

# Step 4: Verify
echo "[4/4] Verifying..."
REMOTE_IMAGE=$(remote "docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}' | grep aether-node | head -1")
echo "  Remote image: ${REMOTE_IMAGE}"

echo ""
echo "=== Done ==="
echo "Ready to run tests:"
echo "  cd aether/tests/integration"
echo "  ./scripts/run-all.sh"
