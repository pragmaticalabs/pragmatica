#!/bin/bash
# Spike test: sudden bursts (100 -> 3000 -> 100 -> 5000) to test recovery.
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env.sh"

echo "Spike test via ${FORGE_NODES}"
k6 run "$SCRIPT_DIR/spike.js"
