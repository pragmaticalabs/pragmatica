#!/bin/bash
# Spike test: sudden bursts (50 -> 1000 -> 50 -> 2000) to test recovery.
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env.sh"

echo "Spike test via ${FORGE_NODES}"
k6 run "$SCRIPT_DIR/spike.js"
