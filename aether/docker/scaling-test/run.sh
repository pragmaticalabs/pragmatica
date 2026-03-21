#!/bin/bash
# Aether Docker Scaling Test — Master Orchestrator
# Usage:
#   ./run.sh all              # Run full test sequence
#   ./run.sh cores            # Phase 1: Start 5 core nodes
#   ./run.sh deploy           # Phase 2: Deploy url-shortener blueprint
#   ./run.sh steady           # Phase 3: Run steady-state k6 load test
#   ./run.sh workers          # Phase 4: Add 7 worker nodes
#   ./run.sh scaling          # Phase 5: Run scaling-verify k6 load test
#   ./run.sh teardown         # Tear down everything
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../../" && pwd)"
LIB_DIR="$SCRIPT_DIR/lib"
K6_DIR="$SCRIPT_DIR/k6"

# Detect version from pom.xml
VERSION=$(grep -m1 '<version>' "$ROOT_DIR/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
echo "Detected project version: $VERSION"

# Blueprint coordinates
BLUEPRINT_GROUP="org.pragmatica.aether.example"
BLUEPRINT_ARTIFACT="url-shortener"
BLUEPRINT_VERSION="$VERSION"
BLUEPRINT_CLASSIFIER="blueprint"
BLUEPRINT_COORDS="$BLUEPRINT_GROUP:$BLUEPRINT_ARTIFACT:$BLUEPRINT_VERSION:$BLUEPRINT_CLASSIFIER"
BLUEPRINT_JAR="$ROOT_DIR/examples/url-shortener/target/url-shortener-${VERSION}-blueprint.jar"
BLUEPRINT_GROUP_PATH="org/pragmatica/aether/example"

COMPOSE_BASE="docker compose -f $SCRIPT_DIR/docker-compose.yml"
COMPOSE_FULL="$COMPOSE_BASE -f $SCRIPT_DIR/docker-compose.workers.yml"

# Management API base (node-1)
MGMT_URL="http://localhost:8080"

teardown() {
    echo "=== Tearing down ==="
    $COMPOSE_FULL down --remove-orphans 2>/dev/null || true
    $COMPOSE_BASE down --remove-orphans 2>/dev/null || true
    echo "Teardown complete."
}

phase_cores() {
    echo "=== Phase 1: Starting 5 core nodes ==="
    $COMPOSE_BASE up --build -d
    "$LIB_DIR/wait-healthy.sh" 5 120
    echo "Phase 1 complete: 5 core nodes healthy."
}

phase_deploy() {
    echo "=== Phase 2: Deploying url-shortener blueprint ==="
    echo "Blueprint: $BLUEPRINT_COORDS"

    # Check blueprint JAR exists
    if [ ! -f "$BLUEPRINT_JAR" ]; then
        echo "ERROR: Blueprint JAR not found at $BLUEPRINT_JAR"
        echo "Build with: mvn install -DskipTests -pl examples/url-shortener -am"
        exit 1
    fi

    # Upload blueprint JAR via Maven protocol
    echo "Uploading blueprint JAR..."
    curl -sf -X PUT "$MGMT_URL/repository/$BLUEPRINT_GROUP_PATH/$BLUEPRINT_ARTIFACT/$BLUEPRINT_VERSION/${BLUEPRINT_ARTIFACT}-${BLUEPRINT_VERSION}-${BLUEPRINT_CLASSIFIER}.jar" \
        --data-binary "@$BLUEPRINT_JAR" \
        || { echo "ERROR: Blueprint upload failed"; exit 1; }
    echo "Blueprint JAR uploaded."

    # Deploy via management API
    echo "Deploying blueprint..."
    curl -sf -X POST "$MGMT_URL/api/blueprint/deploy" \
        -H "Content-Type: application/json" \
        -d "{\"artifact\": \"$BLUEPRINT_COORDS\"}" \
        || { echo "ERROR: Deployment failed"; exit 1; }

    echo ""
    echo "Waiting for deployment to stabilize..."
    sleep 15

    # Verify slices
    echo "Cluster status:"
    curl -sf "$MGMT_URL/api/status" | head -c 500
    echo ""
    echo "Phase 2 complete: blueprint deployed."
}

phase_steady() {
    echo "=== Phase 3: Steady-state load test (cores only) ==="
    if ! command -v k6 &> /dev/null; then
        echo "WARNING: k6 not installed, skipping load test."
        echo "Install: brew install grafana/k6/k6"
        return 0
    fi

    k6 run "$K6_DIR/steady-state.js"
    echo "Phase 3 complete: steady-state test passed."
}

phase_workers() {
    echo "=== Phase 4: Adding 7 worker nodes ==="
    $COMPOSE_FULL up --build -d
    "$LIB_DIR/wait-healthy.sh" 12 180
    echo "Phase 4 complete: 12 nodes healthy (5 core + 7 worker)."
}

phase_scaling() {
    echo "=== Phase 5: Scaling verification load test ==="
    if ! command -v k6 &> /dev/null; then
        echo "WARNING: k6 not installed, skipping load test."
        echo "Install: brew install grafana/k6/k6"
        return 0
    fi

    k6 run "$K6_DIR/scaling-verify.js"
    echo "Phase 5 complete: scaling verification passed."
}

run_all() {
    trap teardown EXIT
    phase_cores
    phase_deploy
    phase_steady
    phase_workers
    phase_scaling
    trap - EXIT
    echo ""
    echo "=== All phases complete ==="
    echo "Cluster is running with 12 nodes. Use './run.sh teardown' when done."
}

case "${1:-all}" in
    all)      run_all ;;
    cores)    phase_cores ;;
    deploy)   phase_deploy ;;
    steady)   phase_steady ;;
    workers)  phase_workers ;;
    scaling)  phase_scaling ;;
    teardown) teardown ;;
    *)
        echo "Usage: $0 {all|cores|deploy|steady|workers|scaling|teardown}"
        exit 1
        ;;
esac
