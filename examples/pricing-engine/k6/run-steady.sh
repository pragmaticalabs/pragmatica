#!/bin/bash
# Steady-state load: constant request rate across all forge nodes.
#
# Usage:
#   ./k6/run-steady.sh              # 500 req/s for 2 minutes
#   ./k6/run-steady.sh 2000 5m      # 2000 req/s for 5 minutes
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env.sh"

export FORGE_RATE="${1:-500}"
export FORGE_DURATION="${2:-2m}"

echo "Steady-state: ${FORGE_RATE} req/s for ${FORGE_DURATION} across $(echo $FORGE_NODES | tr ',' '\n' | wc -l | tr -d ' ') nodes"
k6 run "$SCRIPT_DIR/load-test.js"
