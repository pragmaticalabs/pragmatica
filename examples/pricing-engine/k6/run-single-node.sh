#!/bin/bash
# Single-node load: all traffic to one specific node.
# Useful for isolating per-node behavior or comparing leader vs follower.
#
# Usage:
#   ./k6/run-single-node.sh 0        # All traffic to node 0 (port 8070)
#   ./k6/run-single-node.sh 3 1000   # Node 3 (port 8073) at 1000 req/s
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env.sh"

NODE_INDEX="${1:-0}"
RATE="${2:-500}"
DURATION="${3:-2m}"

# Extract the specific node URL
IFS=',' read -ra NODE_ARRAY <<< "$FORGE_NODES"
NODE_URL="${NODE_ARRAY[$NODE_INDEX]}"

if [ -z "$NODE_URL" ]; then
    echo "ERROR: Node index $NODE_INDEX out of range (0-$((${#NODE_ARRAY[@]} - 1)))"
    exit 1
fi

echo "Single-node: $NODE_URL at ${RATE} req/s for ${DURATION}"

export FORGE_NODES="$NODE_URL"
export FORGE_RATE="$RATE"
export FORGE_DURATION="$DURATION"
k6 run "$SCRIPT_DIR/load-test.js"
