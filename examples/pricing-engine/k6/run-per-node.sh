#!/bin/bash
# Per-node load: independent traffic to each node with separate metrics.
#
# Usage:
#   ./k6/run-per-node.sh            # 100 req/s per node for 2 minutes
#   ./k6/run-per-node.sh 500 5m     # 500 req/s per node for 5 minutes
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env.sh"

export FORGE_RATE_PER_NODE="${1:-100}"
export FORGE_DURATION="${2:-2m}"

echo "Per-node: ${FORGE_RATE_PER_NODE} req/s per node for ${FORGE_DURATION}"
k6 run "$SCRIPT_DIR/per-node.js"
