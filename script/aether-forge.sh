#!/bin/bash
#
# Aether Forge - Standalone cluster simulator with visual dashboard
#
# Usage:
#   ./aether-forge.sh [options]
#
# Environment variables:
#   FORGE_PORT    Dashboard port (default: 8888)
#   CLUSTER_SIZE  Number of simulated nodes (default: 5)
#   LOAD_RATE     Initial requests per second (default: 1000)
#
# Examples:
#   ./aether-forge.sh
#   CLUSTER_SIZE=10 LOAD_RATE=5000 ./aether-forge.sh
#   FORGE_PORT=9999 ./aether-forge.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

JAR_FILE="$PROJECT_DIR/forge/target/forge-0.7.0-SNAPSHOT.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "Forge JAR not found. Building..."
    mvn -f "$PROJECT_DIR/pom.xml" package -pl forge -am -DskipTests -q
fi

java -jar "$JAR_FILE"
