#!/bin/bash
# Forge cluster node configuration — sourced by runner scripts.
# Auto-detects node count, base port, and LB port from forge.toml.
#
# By default, FORGE_NODES points to the passive LB (single endpoint).
# Per-node scripts override this with individual node URLs.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FORGE_TOML="$SCRIPT_DIR/../forge.toml"

# Parse forge.toml for cluster config
NODE_COUNT=$(grep '^nodes' "$FORGE_TOML" | head -1 | tr -d ' ' | cut -d= -f2)
BASE_PORT=$(grep '^app_http_port' "$FORGE_TOML" | head -1 | tr -d ' ' | cut -d= -f2)

NODE_COUNT="${NODE_COUNT:-7}"
BASE_PORT="${BASE_PORT:-8070}"

# Parse LB config (defaults match EmberConfig: enabled=true, port=8080)
LB_PORT="${FORGE_LB_PORT:-8080}"

# Build comma-separated individual node URL list (for per-node scripts)
ALL_NODES=""
for ((i=0; i<NODE_COUNT; i++)); do
    [ -n "$ALL_NODES" ] && ALL_NODES+=","
    ALL_NODES+="http://localhost:$((BASE_PORT + i))"
done

export FORGE_ALL_NODES="$ALL_NODES"
export FORGE_LB_PORT="$LB_PORT"
export FORGE_LB_URL="http://localhost:$LB_PORT"

# Default: route through LB. Override with FORGE_NODES env var for direct node access.
export FORGE_NODES="${FORGE_NODES:-$FORGE_LB_URL}"
