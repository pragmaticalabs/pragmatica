#!/bin/bash
#
# Aether CLI - Command-line interface for cluster management
#
# Usage:
#   ./aether-cli.sh [options] [command]
#
# Options:
#   -c, --connect <host:port>  Node address (default: localhost:8080)
#
# Commands:
#   status    Show cluster status
#   nodes     List cluster nodes
#   slices    List deployed slices
#   metrics   Show cluster metrics
#   health    Health check
#   deploy    Deploy a slice
#   scale     Scale a slice
#   undeploy  Remove a slice
#   artifact  Artifact operations
#
# Examples:
#   ./aether-cli.sh status
#   ./aether-cli.sh --connect node1:8080 nodes
#   ./aether-cli.sh  # Start REPL mode

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

JAR_FILE="$PROJECT_DIR/cli/target/cli-0.6.2-SNAPSHOT.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "CLI JAR not found. Building..."
    mvn -f "$PROJECT_DIR/pom.xml" package -pl cli -am -DskipTests -q
fi

java -jar "$JAR_FILE" "$@"
