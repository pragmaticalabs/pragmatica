#!/bin/bash
# Run Pricing Engine in Aether Forge
#
# Usage:
#   ./run-forge.sh              Build + start Forge cluster
#   ./run-forge.sh --skip-build Start without rebuilding
#   ./run-forge.sh --with-load  Start with built-in load generator
#   ./run-forge.sh --forge-from-source  Contributor path: build Forge and the platform from this checkout
#
# Forge comes from the installer (see ../run-forge-lib.sh for the lookup order):
#   curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/aether/install.sh | sh
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [ ! -f "$SCRIPT_DIR/../run-forge-lib.sh" ]; then
    echo "ERROR: $SCRIPT_DIR/../run-forge-lib.sh not found; run this script from a pragmatica checkout."
    exit 1
fi
source "$SCRIPT_DIR/../run-forge-lib.sh"

VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout -f "$SCRIPT_DIR/pom.xml")
BLUEPRINT_COORDS="org.pragmatica.aether.example:pricing-engine:${VERSION}:blueprint"

WITH_LOAD=false
SKIP_BUILD=false
FROM_SOURCE=false

for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=true ;;
        --forge-from-source) FROM_SOURCE=true ;;
        --with-load)  WITH_LOAD=true ;;
    esac
done

# Resolve Forge before building, so a missing install fails in seconds, not after the Maven build.
if [ "$FROM_SOURCE" = true ]; then
    forge_from_source "$PROJECT_ROOT" "$SKIP_BUILD" || exit 1
else
    forge_resolve || exit 1
fi

if [ "$SKIP_BUILD" = false ]; then
    echo "Building pricing-engine slice..."
    if [ "$FROM_SOURCE" = true ]; then
        # Contributor path: build the platform modules this example needs from the checkout.
        mvn -f "$PROJECT_ROOT/pom.xml" install -DskipTests -pl examples/pricing-engine -am -q
    else
        # The example builds on its own; platform artifacts come from Maven Central.
        mvn -f "$SCRIPT_DIR/pom.xml" install -DskipTests -q
    fi
fi

echo ""
echo "Starting Aether Forge with Pricing Engine..."
echo "  Dashboard:  http://localhost:8888"
echo "  App HTTP:   http://localhost:8070 (nodes: 8070-8076)"
echo "  Management: http://localhost:5150"
echo "  Blueprint:  $BLUEPRINT_COORDS"
echo ""
echo "Test:"
echo "  curl -s -X POST http://localhost:8070/api/v1/pricing/calculate \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"productId\":\"WIDGET-D\",\"quantity\":3,\"regionCode\":\"US-CA\",\"couponCode\":\"SAVE20\"}' | jq"
echo ""
echo "Load test (k6):"
echo "  k6 run k6/load-test.js                # Steady-state 500 req/s"
echo "  k6 run k6/ramp-up.js                  # Find saturation point"
echo "  k6 run k6/per-node.js                 # Per-node comparison"
echo "  k6 run k6/spike.js                    # Spike recovery"
echo ""

FORGE_ARGS=(
    --config "$SCRIPT_DIR/forge.toml"
    --blueprint "$BLUEPRINT_COORDS"
)

if [ "$WITH_LOAD" = true ]; then
    FORGE_ARGS+=(--load-config "$SCRIPT_DIR/load-config.toml" --auto-start)
    echo "(Built-in load generator enabled)"
fi

# FORGE_JVM_OPTS: extra JVM flags, honoured for a jar and a launcher alike (see forge_exec in ../run-forge-lib.sh).
forge_exec "${FORGE_ARGS[@]}"
