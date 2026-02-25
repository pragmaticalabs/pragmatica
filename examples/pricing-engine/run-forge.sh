#!/bin/bash
# Run Pricing Engine in Aether Forge with load generation
#
# Usage:
#   ./run-forge.sh              Build + start Forge with load generator
#   ./run-forge.sh --skip-build Start Forge without rebuilding
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

FORGE_JAR="$PROJECT_ROOT/aether/forge/forge-core/target/aether-forge.jar"

# Build unless --skip-build
if [ "$1" != "--skip-build" ]; then
    echo "Building pricing-engine slice..."
    mvn -f "$PROJECT_ROOT/pom.xml" install -DskipTests -pl examples/pricing-engine -q

    if [ ! -f "$FORGE_JAR" ]; then
        echo "Building Forge..."
        mvn -f "$PROJECT_ROOT/pom.xml" package -pl aether/forge/forge-core -am -DskipTests -q
    fi
fi

if [ ! -f "$FORGE_JAR" ]; then
    echo "ERROR: Forge JAR not found at $FORGE_JAR"
    echo "Run without --skip-build to build it."
    exit 1
fi

echo ""
echo "Starting Aether Forge with Pricing Engine..."
echo "  Dashboard: http://localhost:8888"
echo "  App HTTP:  http://localhost:8070"
echo ""
echo "Test endpoints:"
echo "  curl -X POST http://localhost:8070/api/v1/pricing/calculate \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"productId\":\"WIDGET-D\",\"quantity\":3,\"regionCode\":\"US-CA\",\"couponCode\":\"SAVE20\"}'"
echo ""
echo "  curl http://localhost:8070/api/v1/pricing/analytics/high-value"
echo ""

exec java -jar "$FORGE_JAR" \
    --config "$SCRIPT_DIR/forge.toml" \
    --blueprint "$SCRIPT_DIR/target/blueprint.toml" \
    --load-config "$SCRIPT_DIR/load-config.toml" \
    --auto-start
