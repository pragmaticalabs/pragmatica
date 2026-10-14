#!/bin/bash
# Run Notification Hub in Aether Forge
#
# Usage:
#   ./run-forge.sh              Build + start Forge cluster
#   ./run-forge.sh --skip-build Start without rebuilding
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
BLUEPRINT_COORDS="org.pragmatica.aether.example:notification-hub-notification-service:${VERSION}:blueprint"

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
    echo "Building notification-hub slices..."
    if [ "$FROM_SOURCE" = true ]; then
        # Contributor path: build the platform modules this example needs from the checkout.
        mvn -f "$PROJECT_ROOT/pom.xml" install -DskipTests -pl examples/notification-hub/notification-service,examples/notification-hub/notification-analytics -am -q
    else
        # The example builds on its own; platform artifacts come from Maven Central.
        # notification-emailer is left out: it needs resource-notification, which is not on Maven Central, and the
        # blueprint does not deploy it (#953).
        mvn -f "$SCRIPT_DIR/pom.xml" install -DskipTests -pl notification-service,notification-analytics -am -q
    fi
fi

echo ""
echo "Starting Aether Forge with Notification Hub..."
echo "  Dashboard:  http://localhost:8888"
echo "  App HTTP:   http://localhost:8070 (nodes: 8070-8076)"
echo "  Management: http://localhost:5150"
echo "  Blueprint:  $BLUEPRINT_COORDS"
echo ""
echo "Test:"
echo "  # Health check (public)"
echo "  curl -s http://localhost:8070/api/v1/notifications/health | jq"
echo ""
echo "  # Send notification (authenticated)"
echo '  curl -s -X POST http://localhost:8070/api/v1/notifications/ \'
echo '    -H "Content-Type: application/json" \'
echo '    -H "Authorization: Bearer <token>" \'
echo "    -d '{\"message\":\"Hello world\",\"channel\":\"general\"}' | jq"
echo ""
echo "  # List recent notifications"
echo "  curl -s http://localhost:8070/api/v1/notifications/ | jq"
echo ""
echo "  # Analytics"
echo "  curl -s http://localhost:8070/api/v1/analytics/ | jq"
echo ""

FORGE_ARGS=(
    --config "$SCRIPT_DIR/forge.toml"
    --blueprint "$BLUEPRINT_COORDS"
)

# FORGE_JVM_OPTS: extra JVM flags, honoured for a jar and a launcher alike (see forge_exec in ../run-forge-lib.sh).
forge_exec "${FORGE_ARGS[@]}"
