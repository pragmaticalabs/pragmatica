#!/bin/bash
# Forge cluster node configuration — sourced by runner scripts.
# Auto-detects node count and base port from forge.toml.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FORGE_TOML="$SCRIPT_DIR/../forge.toml"

# Parse forge.toml for cluster config
NODE_COUNT=$(grep '^nodes' "$FORGE_TOML" | head -1 | tr -d ' ' | cut -d= -f2)
BASE_PORT=$(grep '^app_http_port' "$FORGE_TOML" | head -1 | tr -d ' ' | cut -d= -f2)

NODE_COUNT="${NODE_COUNT:-7}"
BASE_PORT="${BASE_PORT:-8070}"

# Build comma-separated node URL list
NODES=""
for ((i=0; i<NODE_COUNT; i++)); do
    [ -n "$NODES" ] && NODES+=","
    NODES+="http://localhost:$((BASE_PORT + i))"
done

export FORGE_NODES="$NODES"
