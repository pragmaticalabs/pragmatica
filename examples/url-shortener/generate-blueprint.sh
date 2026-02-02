#!/bin/bash
# Generate blueprint.toml from slice manifests
set -e

echo "Generating blueprint..."
mvn package jbct:generate-blueprint -DskipTests

BLUEPRINT="target/blueprint.toml"

if [ -f "$BLUEPRINT" ]; then
    echo ""
    echo "Blueprint generated: $BLUEPRINT"
    echo ""
    cat "$BLUEPRINT"
else
    echo "ERROR: Blueprint generation failed"
    exit 1
fi
