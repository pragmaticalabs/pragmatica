#!/bin/bash
# Wait for Aether nodes to be healthy
# Usage: wait-healthy.sh <expected-nodes> [timeout-seconds]
# Checks management ports: cores 8080-8084, workers 8185-8191
set -e

EXPECTED=${1:-5}
TIMEOUT=${2:-120}

# Core management ports: 8080-8084
# Worker management ports: 8185-8191
CORE_PORTS=(8080 8081 8082 8083 8084)
WORKER_PORTS=(8185 8186 8187 8188 8189 8190 8191)
ALL_PORTS=("${CORE_PORTS[@]}" "${WORKER_PORTS[@]}")

echo "Waiting for $EXPECTED nodes to be healthy (timeout: ${TIMEOUT}s)..."

DEADLINE=$((SECONDS + TIMEOUT))
while [ $SECONDS -lt $DEADLINE ]; do
    HEALTHY=0
    for i in $(seq 0 $((EXPECTED - 1))); do
        PORT=${ALL_PORTS[$i]}
        if curl -sf "http://localhost:$PORT/api/health" > /dev/null 2>&1; then
            HEALTHY=$((HEALTHY + 1))
        fi
    done
    if [ $HEALTHY -ge $EXPECTED ]; then
        echo "All $EXPECTED nodes healthy."
        exit 0
    fi
    echo "  $HEALTHY/$EXPECTED healthy..."
    sleep 3
done

echo "ERROR: Only $HEALTHY/$EXPECTED nodes healthy after ${TIMEOUT}s"
exit 1
