#!/bin/bash
# Run URL Shortener in Aether Forge (no load generator — use k6 scripts instead)
#
# Usage:
#   ./run-forge.sh              Build + start Forge cluster
#   ./run-forge.sh --skip-build Start without rebuilding
#   ./run-forge.sh --with-load  Start with built-in load generator
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

FORGE_JAR="$PROJECT_ROOT/aether/forge/forge-core/target/aether-forge.jar"

WITH_LOAD=false
SKIP_BUILD=false

for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=true ;;
        --with-load)  WITH_LOAD=true ;;
    esac
done

if [ "$SKIP_BUILD" = false ]; then
    echo "Building url-shortener slice..."
    mvn -f "$PROJECT_ROOT/pom.xml" install -DskipTests -pl examples/url-shortener -am -q

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
echo "Starting Aether Forge with URL Shortener..."
echo "  Dashboard:  http://localhost:8888"
echo "  App HTTP:   http://localhost:8070 (nodes: 8070-8076)"
echo "  Management: http://localhost:5150"
echo ""
echo "Test:"
echo "  curl -s -X POST http://localhost:8070/api/v1/urls/ \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"url\":\"https://example.com/hello\"}' | jq"
echo ""
echo "  curl -s http://localhost:8070/api/v1/urls/{shortCode} | jq"
echo ""
echo "Load test (k6):"
echo "  k6 run k6/load-test.js                # Steady-state 500 req/s"
echo "  k6 run k6/ramp-up.js                  # Find saturation point"
echo "  k6 run k6/per-node.js                 # Per-node comparison"
echo "  k6 run k6/spike.js                    # Spike recovery"
echo ""

FORGE_ARGS=(
    --config "$SCRIPT_DIR/forge.toml"
    --blueprint "$SCRIPT_DIR/target/blueprint.toml"
)

if [ "$WITH_LOAD" = true ]; then
    FORGE_ARGS+=(--load-config "$SCRIPT_DIR/load-config.toml" --auto-start)
    echo "(Built-in load generator enabled)"
fi

exec java -jar "$FORGE_JAR" "${FORGE_ARGS[@]}"
