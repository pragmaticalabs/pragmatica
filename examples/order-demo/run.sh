#!/bin/bash
# Order Demo Runner Script
# Builds the demo slices and runs Forge with this configuration

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "=================================================="
echo "    Order Demo - Aether Forge"
echo "=================================================="

# Build the demo slices first
echo "Building order-demo slices..."
cd "$SCRIPT_DIR"
mvn package -DskipTests -q

# Build Forge if needed
echo "Building Forge..."
cd "$ROOT_DIR/forge"
mvn package -DskipTests -q

# Run Forge
echo "Starting Forge..."
echo ""

# Set environment variables
export FORGE_PORT=${FORGE_PORT:-8888}
export CLUSTER_SIZE=${CLUSTER_SIZE:-5}
export LOAD_RATE=${LOAD_RATE:-1000}

# Run with the shaded JAR
java -jar "$ROOT_DIR/forge/target/forge-0.6.2-shaded.jar"
