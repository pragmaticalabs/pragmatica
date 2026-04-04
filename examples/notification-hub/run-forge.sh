#!/bin/bash
# Run Notification Hub in Aether Forge
#
# Usage:
#   ./run-forge.sh              Build + start Forge cluster
#   ./run-forge.sh --skip-build Start without rebuilding
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

FORGE_JAR="$PROJECT_ROOT/aether/forge/forge-core/target/aether-forge.jar"
BLUEPRINT_COORDS="org.pragmatica.aether.example:notification-hub-notification-service:1.0.0-rc1:blueprint"

SKIP_BUILD=false

for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=true ;;
    esac
done

if [ "$SKIP_BUILD" = false ]; then
    echo "Building notification-hub slices..."
    mvn -f "$PROJECT_ROOT/pom.xml" install -DskipTests -pl examples/notification-hub/notification-service,examples/notification-hub/notification-analytics -am -q

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

exec java -jar "$FORGE_JAR" "${FORGE_ARGS[@]}"
