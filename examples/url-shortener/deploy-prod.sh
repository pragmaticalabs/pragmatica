#!/bin/bash
# Deploy slice to production Aether cluster
# Requires: aether CLI configured for production environment
set -e

echo "WARNING: Deploying to PRODUCTION"
echo ""
read -p "Are you sure? (yes/no): " confirm

if [ "$confirm" != "yes" ]; then
    echo "Deployment cancelled."
    exit 1
fi

echo ""
echo "Building and verifying..."
mvn clean verify

BLUEPRINT="target/blueprint.toml"
if [ ! -f "$BLUEPRINT" ]; then
    echo "ERROR: Blueprint not found."
    exit 1
fi

echo ""
echo "Pushing artifacts to production cluster..."
aether artifact push --env prod

echo ""
echo "Deployed to production."
