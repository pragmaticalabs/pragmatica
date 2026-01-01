#!/bin/bash
#
# Aether Node - Start a cluster node
#
# Usage:
#   ./aether-node.sh [options]
#
# Options:
#   --node-id=<id>            Node identifier (default: random)
#   --port=<port>             Cluster port (default: 8090)
#   --peers=<host:port,...>   Comma-separated peer addresses
#
# Peer format:
#   host:port           Auto-generate node ID
#   nodeId:host:port    Explicit node ID
#
# Examples:
#   # Start single node
#   ./aether-node.sh
#
#   # Start node with specific ID and port
#   ./aether-node.sh --node-id=node-1 --port=8091
#
#   # Start node and join existing cluster
#   ./aether-node.sh --node-id=node-2 --port=8092 --peers=localhost:8091,localhost:8092
#
#   # Start 3-node cluster (run in separate terminals)
#   ./aether-node.sh --node-id=node-1 --port=8091 --peers=localhost:8091,localhost:8092,localhost:8093
#   ./aether-node.sh --node-id=node-2 --port=8092 --peers=localhost:8091,localhost:8092,localhost:8093
#   ./aether-node.sh --node-id=node-3 --port=8093 --peers=localhost:8091,localhost:8092,localhost:8093

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

JAR_FILE="$PROJECT_DIR/node/target/node-0.6.4-SNAPSHOT.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "Node JAR not found. Building..."
    mvn -f "$PROJECT_DIR/pom.xml" package -pl node -am -DskipTests -q
fi

java -jar "$JAR_FILE" "$@"
