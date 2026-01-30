#!/bin/bash
#
# Aether Forge - Standalone cluster simulator with visual dashboard
#
# Usage:
#   ./aether-forge.sh [options]
#
# CLI options:
#   --blueprint <file.toml>     Blueprint to deploy on startup
#   --load-config <file.toml>   Load test configuration
#   --config <forge.toml>       Forge cluster configuration
#   --auto-start                Start load generation after config loaded
#
# Environment variables:
#   FORGE_PORT    Dashboard port (default: 8888)
#   CLUSTER_SIZE  Number of simulated nodes (default: 5)
#   LOAD_RATE     Initial requests per second (default: 1000)
#
# Examples:
#   ./aether-forge.sh
#   ./aether-forge.sh --blueprint my-app.toml
#   ./aether-forge.sh --blueprint my-app.toml --load-config load.toml
#   CLUSTER_SIZE=10 ./aether-forge.sh --blueprint my-app.toml

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

JAR_FILE="$PROJECT_DIR/forge/target/aether-forge.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "Forge JAR not found. Building..."
    mvn -f "$PROJECT_DIR/pom.xml" package -pl forge -am -DskipTests -q
fi

exec java -jar "$JAR_FILE" "$@"
