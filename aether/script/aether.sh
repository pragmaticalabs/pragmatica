#!/bin/bash
#
# Aether - Command-line interface for cluster management
#
# Usage:
#   ./aether.sh [options] [command]
#
# Options:
#   -c, --connect <host:port>  Node address (default: localhost:8080)
#
# Commands:
#   status         Show cluster status
#   nodes          List cluster nodes
#   slices         List deployed slices
#   metrics        Show cluster metrics
#   health         Health check
#   deploy         Deploy a slice
#   scale          Scale a slice
#   undeploy       Remove a slice
#   blueprint      Blueprint operations (apply)
#   artifact       Artifact operations (deploy, list, versions)
#
# Examples:
#   ./aether.sh status
#   ./aether.sh --connect node1:8080 nodes
#   ./aether.sh blueprint apply my-app.toml
#   ./aether.sh  # Start REPL mode

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

JAR_FILE="$PROJECT_DIR/cli/target/aether.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "CLI JAR not found. Building..."
    mvn -f "$PROJECT_DIR/pom.xml" package -pl cli -am -DskipTests -q
fi

# Mirrors the installed launcher (~/.aether/bin/aether) so a dev run can carry the
# same JVM options - notably -Dmaven.repo.local, which decides which Maven local
# repository `artifacts push` reads fixtures from.
exec java ${AETHER_JAVA_OPTS:-} -jar "$JAR_FILE" "$@"
