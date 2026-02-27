#!/bin/bash
# Steady-state load: constant request rate across all forge nodes.
#
# Usage:
#   ./k6/run-steady.sh                  # 500 req/s for 2 minutes (30s warmup)
#   ./k6/run-steady.sh 5000 1m          # 5000 req/s for 1 minute (30s warmup)
#   ./k6/run-steady.sh 5000 1m 45s      # 5000 req/s for 1 minute (45s warmup)
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env.sh"

export FORGE_RATE="${1:-500}"
export FORGE_DURATION="${2:-2m}"
export FORGE_WARMUP="${3:-30s}"

echo "Steady-state: ${FORGE_RATE} req/s for ${FORGE_DURATION} (warmup: ${FORGE_WARMUP}) across $(echo $FORGE_NODES | tr ',' '\n' | wc -l | tr -d ' ') nodes"
k6 run "$SCRIPT_DIR/load-test.js"
