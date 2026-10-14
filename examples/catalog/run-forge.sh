#!/bin/bash
# Run the Catalog example on Aether Forge (single-JVM, multi-node).
#
# Usage:
#   ./run-forge.sh              Build + start Forge cluster
#   ./run-forge.sh --skip-build Start without rebuilding
#   ./run-forge.sh --forge-from-source  Contributor path: build Forge and the platform from this checkout
#
# Forge comes from the installer (see ../run-forge-lib.sh for the lookup order):
#   curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/aether/install.sh | sh
#
# The catalog slice is resource-free (no database), so there is nothing else to start.
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [ ! -f "$SCRIPT_DIR/../run-forge-lib.sh" ]; then
    echo "ERROR: $SCRIPT_DIR/../run-forge-lib.sh not found; run this script from a pragmatica checkout." >&2
    exit 1
fi
source "$SCRIPT_DIR/../run-forge-lib.sh"

VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout -f "$SCRIPT_DIR/pom.xml")
BLUEPRINT_COORDS="org.pragmatica.aether.example:catalog:${VERSION}:blueprint"

SKIP_BUILD=false
FROM_SOURCE=false
for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=true ;;
        --forge-from-source) FROM_SOURCE=true ;;
    esac
done

# Resolve Forge before building, so a missing install fails in seconds, not after the Maven build.
if [ "$FROM_SOURCE" = true ]; then
    forge_from_source "$PROJECT_ROOT" "$SKIP_BUILD" || exit 1
else
    forge_resolve || exit 1
fi

if [ "$SKIP_BUILD" = false ]; then
    echo "Building catalog slice..."
    if [ "$FROM_SOURCE" = true ]; then
        # Contributor path: build the platform modules this example needs from the checkout.
        mvn -f "$PROJECT_ROOT/pom.xml" install -DskipTests -pl examples/catalog -am -q
    else
        # The example builds on its own; platform artifacts come from Maven Central.
        # -P '!test-plan-gate': the root pom's test-plan-gate profile adds test-plan-witness to any module with
        # src/test, and that artifact is not on Maven Central (#953).
        mvn -f "$SCRIPT_DIR/pom.xml" install -DskipTests -P '!test-plan-gate' -q
    fi
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

# FORGE_JVM_OPTS: extra JVM flags, honoured for a jar and a launcher alike (see forge_exec in ../run-forge-lib.sh).
forge_exec "${FORGE_ARGS[@]}"
