#!/bin/bash
# Run Step Composition Example in Aether Forge
#
# Usage:
#   ./run-forge.sh              Build + start Forge cluster
#   ./run-forge.sh --skip-build Start without rebuilding
#   ./run-forge.sh --with-load  Start with built-in load generator
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

FORGE_JAR="$PROJECT_ROOT/aether/forge/forge-core/target/aether-forge.jar"

VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout -f "$SCRIPT_DIR/pom.xml")
BLUEPRINT_COORDS="org.pragmatica.aether.example:step-composition:${VERSION}:blueprint"

WITH_LOAD=false
SKIP_BUILD=false

for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=true ;;
        --with-load)  WITH_LOAD=true ;;
    esac
done

if [ "$SKIP_BUILD" = false ]; then
    echo "Building step-composition slice..."
    mvn -f "$SCRIPT_DIR/pom.xml" install -DskipTests -q

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
echo "Starting Aether Forge with Step Composition Example..."
echo "  Dashboard:  http://localhost:8888"
echo "  App HTTP:   http://localhost:8070 (nodes: 8070-8072)"
echo "  Management: http://localhost:5150"
echo "  Blueprint:  $BLUEPRINT_COORDS"
echo ""
echo "Test:"
echo '  curl -s -X POST http://localhost:8070/api/v1/orders/ \'
echo '    -H "Content-Type: application/json" \'
echo '    -d '"'"'{"customerId":"CUST-001","productId":"LAPTOP-PRO","quantity":2,"amount":"99.99"}'"'"' | jq'
echo ""

FORGE_ARGS=(
    --config "$SCRIPT_DIR/forge.toml"
    --blueprint "$BLUEPRINT_COORDS"
)

if [ "$WITH_LOAD" = true ]; then
    FORGE_ARGS+=(--load-config "$SCRIPT_DIR/load-config.toml" --auto-start)
    echo "(Built-in load generator enabled)"
fi

# FORGE_JVM_OPTS: extra JVM flags (e.g. JDWP remote debug -- see docs/slice-developers/forge-guide.md#debugging).
# Split with read -ra: flags word-split without glob expansion (a literal * in JDWP address= survives, #664).
read -ra FORGE_JVM_OPTS_ARR <<< "${FORGE_JVM_OPTS:-}"
exec java "${FORGE_JVM_OPTS_ARR[@]}" -jar "$FORGE_JAR" "${FORGE_ARGS[@]}"
