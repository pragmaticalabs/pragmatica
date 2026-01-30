#!/bin/bash
# Deploy slice to test Aether cluster
# Requires: aether CLI configured for test environment
set -e

echo "Building and installing..."
mvn clean install -DskipTests

BLUEPRINT="target/blueprint.toml"
if [ ! -f "$BLUEPRINT" ]; then
    echo "ERROR: Blueprint not found. Run: mvn package jbct:generate-blueprint"
    exit 1
fi

echo ""
echo "Pushing artifacts to test cluster..."
aether artifact push --env test

echo ""
echo "Deployed to test environment."
