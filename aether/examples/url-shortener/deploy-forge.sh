#!/bin/bash
# Deploy slice to local Aether Forge (development)
# Uses local Maven repository - Forge reads from ~/.m2/repository
set -e

echo "Building and installing to local repository..."
mvn clean install -DskipTests

echo ""
echo "Slice installed to local Maven repository."
echo "Forge (with repositories=["local"]) will automatically pick up changes."
echo ""
echo "If Forge is running, the slice is now available."
