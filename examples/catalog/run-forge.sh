#!/bin/bash
# Run the Catalog example on Aether Forge (single-JVM, multi-node).
#
# Usage:
#   ./run-forge.sh              Build + start Forge cluster
#   ./run-forge.sh --skip-build Start without rebuilding
#
# The catalog slice is resource-free (no database), so there is nothing else to start.
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

FORGE_JAR="$PROJECT_ROOT/aether/forge/forge-core/target/aether-forge.jar"

VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout -f "$SCRIPT_DIR/pom.xml")
BLUEPRINT_COORDS="org.pragmatica.aether.example:catalog:${VERSION}:blueprint"

SKIP_BUILD=false
for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=true ;;
    esac
done

if [ "$SKIP_BUILD" = false ]; then
    echo "Building catalog slice..."
    mvn -f "$PROJECT_ROOT/pom.xml" install -DskipTests -pl examples/catalog -am -q

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
echo "Starting Aether Forge with Catalog..."
echo "  Dashboard:  http://localhost:8888"
echo "  App HTTP:   http://localhost:8070 (nodes: 8070-8076)"
echo "  Management: http://localhost:5150"
echo "  Blueprint:  $BLUEPRINT_COORDS"
echo ""
echo "Version detection mode is set in aether.toml ([app-http] api_versioning_detection)."
echo ""
echo "Try (path mode):"
echo "  curl -i http://localhost:8070/api/catalog/v1/items      # deprecated: note Deprecation/Sunset headers"
echo "  curl    http://localhost:8070/api/catalog/v2/items      # rich shape (currency + tags)"
echo "  curl    http://localhost:8070/api/catalog/v2/items.csv  # produces text/csv"
echo "  curl    http://localhost:8070/api/catalog/v2/items/1/image -o img.bin  # octet-stream"
echo "  curl    http://localhost:8070/api/versions              # declared versions"
echo ""

FORGE_ARGS=(
    --config "$SCRIPT_DIR/forge.toml"
    --blueprint "$BLUEPRINT_COORDS"
)

# FORGE_JVM_OPTS: extra JVM flags (e.g. JDWP remote debug -- see docs/slice-developers/forge-guide.md#debugging).
# Deliberately unquoted: several space-separated flags must word-split.
exec java ${FORGE_JVM_OPTS:-} -jar "$FORGE_JAR" "${FORGE_ARGS[@]}"
