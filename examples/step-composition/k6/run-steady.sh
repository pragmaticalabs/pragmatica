#!/bin/bash
# Steady-state load: constant request rate via passive LB.
#
# Usage:
#   ./k6/run-steady.sh                  # 200 req/s for 2 minutes (30s warmup)
#   ./k6/run-steady.sh 1000 5m          # 1000 req/s for 5 minutes (30s warmup)
#   ./k6/run-steady.sh 1000 5m 45s      # 1000 req/s for 5 minutes (45s warmup)
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env.sh"

export FORGE_RATE="${1:-200}"
export FORGE_DURATION="${2:-2m}"
export FORGE_WARMUP="${3:-30s}"

echo "Steady-state: ${FORGE_RATE} req/s for ${FORGE_DURATION} (warmup: ${FORGE_WARMUP}) via ${FORGE_NODES}"
k6 run "$SCRIPT_DIR/load-test.js"
