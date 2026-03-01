#!/bin/bash
# Ramp-up: 20 -> 100 -> 200 -> 500 -> 1000 VUs to find saturation point.
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/env.sh"

echo "Ramp-up test via ${FORGE_NODES}"
k6 run "$SCRIPT_DIR/ramp-up.js"
