#!/bin/bash
# Aether Docker Scaling Test — Master Orchestrator
# Usage:
#   ./run.sh all              # Run full test sequence
#   ./run.sh cores            # Phase 1: Start 5 core nodes
#   ./run.sh monitoring       # Phase 1b: Start Prometheus + Grafana
#   ./run.sh deploy           # Phase 2: Deploy url-shortener blueprint
#   ./run.sh steady           # Phase 3: Run steady-state k6 load test
#   ./run.sh workers          # Phase 4: Add 7 worker nodes
#   ./run.sh scaling          # Phase 5: Run scaling-verify k6 load test
#   ./run.sh soak             # 4-hour soak test with chaos injection
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
COMPOSE_MONITORING="$COMPOSE_BASE -f $SCRIPT_DIR/docker-compose.monitoring.yml"
COMPOSE_ALL="$COMPOSE_FULL -f $SCRIPT_DIR/docker-compose.monitoring.yml"

# Management API base (node-1)
MGMT_URL="http://localhost:8080"

upload_artifact() {
    local group_path="$1" artifact_id="$2" version="$3" filename="$4" filepath="$5"
    if [ ! -f "$filepath" ]; then
        echo "ERROR: Artifact not found: $filepath"
        exit 1
    fi
    echo "  Uploading $filename..."
    curl -sf -X PUT "$MGMT_URL/repository/$group_path/$artifact_id/$version/$filename" \
        --data-binary "@$filepath" \
        || { echo "ERROR: Upload failed for $filename"; exit 1; }
}

teardown() {
    echo "=== Tearing down ==="
    $COMPOSE_ALL down --remove-orphans 2>/dev/null || true
    $COMPOSE_FULL down --remove-orphans 2>/dev/null || true
    $COMPOSE_MONITORING down --remove-orphans 2>/dev/null || true
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

    # Upload all artifacts via Maven protocol (blueprint + slice JARs)
    upload_artifact "$BLUEPRINT_GROUP_PATH" "$BLUEPRINT_ARTIFACT" "$BLUEPRINT_VERSION" "${BLUEPRINT_ARTIFACT}-${BLUEPRINT_VERSION}-${BLUEPRINT_CLASSIFIER}.jar" "$BLUEPRINT_JAR"
    upload_artifact "$BLUEPRINT_GROUP_PATH" "url-shortener-url-shortener" "$BLUEPRINT_VERSION" "url-shortener-url-shortener-${BLUEPRINT_VERSION}.jar" "$ROOT_DIR/examples/url-shortener/target/url-shortener-url-shortener-${BLUEPRINT_VERSION}.jar"
    upload_artifact "$BLUEPRINT_GROUP_PATH" "url-shortener-analytics" "$BLUEPRINT_VERSION" "url-shortener-analytics-${BLUEPRINT_VERSION}.jar" "$ROOT_DIR/examples/url-shortener/target/url-shortener-analytics-${BLUEPRINT_VERSION}.jar"
    echo "All artifacts uploaded."

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
    $COMPOSE_FULL up --build -d --no-recreate
    "$LIB_DIR/wait-healthy.sh" 12 180
    echo "Phase 4 complete: 12 nodes healthy (5 core + 7 worker)."
}

phase_monitoring() {
    echo "=== Starting Monitoring ==="
    $COMPOSE_MONITORING up -d
    echo "Prometheus: http://localhost:9090"
    echo "Grafana:    http://localhost:3000 (admin/admin)"
    echo "Monitoring started."
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

phase_soak() {
    echo "=== Soak Test: 4-hour sustained load with chaos injection ==="
    if ! command -v k6 &> /dev/null; then
        echo "WARNING: k6 not installed, skipping soak test."
        echo "Install: brew install grafana/k6/k6"
        return 0
    fi

    local REPORT_DIR="$SCRIPT_DIR/reports"
    mkdir -p "$REPORT_DIR"
    local TIMESTAMP
    TIMESTAMP=$(date '+%Y%m%d-%H%M%S')

    echo "Starting monitoring..."
    phase_monitoring

    echo "Starting chaos controller in background..."
    "$K6_DIR/chaos-controller.sh" > "$REPORT_DIR/chaos-$TIMESTAMP.log" 2>&1 &
    local CHAOS_PID=$!
    echo "Chaos controller PID: $CHAOS_PID"

    echo "Starting k6 soak test..."
    k6 run --out csv="$REPORT_DIR/soak-$TIMESTAMP.csv" "$K6_DIR/soak-test.js" 2>&1 | tee "$REPORT_DIR/soak-$TIMESTAMP.log"
    local K6_EXIT=$?

    echo "Waiting for chaos controller to finish..."
    wait $CHAOS_PID 2>/dev/null || true

    echo ""
    echo "=== Soak Test Complete ==="
    echo "k6 exit code: $K6_EXIT"
    echo "Reports:"
    echo "  k6 output:  $REPORT_DIR/soak-$TIMESTAMP.log"
    echo "  k6 CSV:     $REPORT_DIR/soak-$TIMESTAMP.csv"
    echo "  Chaos log:  $REPORT_DIR/chaos-$TIMESTAMP.log"
    echo "  Grafana:    http://localhost:3000 (dashboards for visual analysis)"

    echo ""
    echo "=== Running Soak Verdict ==="
    local VERDICT_EXIT=0
    "$LIB_DIR/soak-verdict.sh" || VERDICT_EXIT=$?

    echo ""
    echo "=== Generating Soak Report ==="
    local REPORT_FILE="$REPORT_DIR/soak-report-$TIMESTAMP.md"
    "$LIB_DIR/soak-report.sh" "$REPORT_FILE"
    echo "Report written to: $REPORT_FILE"

    if [ "$K6_EXIT" -ne 0 ]; then
        return $K6_EXIT
    fi
    return $VERDICT_EXIT
}

run_all() {
    trap teardown EXIT
    phase_cores
    phase_monitoring
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
    all)        run_all ;;
    cores)      phase_cores ;;
    monitoring) phase_monitoring ;;
    deploy)     phase_deploy ;;
    steady)     phase_steady ;;
    workers)    phase_workers ;;
    scaling)    phase_scaling ;;
    soak)       phase_soak ;;
    teardown)   teardown ;;
    *)
        echo "Usage: $0 {all|cores|monitoring|deploy|steady|workers|scaling|soak|teardown}"
        exit 1
        ;;
esac
