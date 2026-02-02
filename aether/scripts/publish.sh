#!/bin/bash

# Publish script for Maven Central
# Publishes only the public API and infrastructure modules
# Usage: ./scripts/publish.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

# Modules to publish
MODULES=(
    "slice-api"
    "slice-annotations"
    "infra-api"
    "infra-slices/infra-aspect"
    "infra-slices/infra-blob"
    "infra-slices/infra-cache"
    "infra-slices/infra-config"
    "infra-slices/infra-database"
    "infra-slices/infra-feature"
    "infra-slices/infra-http"
    "infra-slices/infra-lock"
    "infra-slices/infra-outbox"
    "infra-slices/infra-pubsub"
    "infra-slices/infra-ratelimit"
    "infra-slices/infra-scheduler"
    "infra-slices/infra-secrets"
    "infra-slices/infra-server"
    "infra-slices/infra-statemachine"
    "infra-slices/infra-streaming"
)

# Build module list for Maven
MODULE_LIST=$(IFS=,; echo "${MODULES[*]}")

echo "=========================================="
echo "Aether Maven Central Publishing"
echo "=========================================="
echo ""
echo "Modules to publish:"
for module in "${MODULES[@]}"; do
    echo "  - $module"
done
echo ""

# Verify we're on main branch
CURRENT_BRANCH=$(git branch --show-current)
if [ "$CURRENT_BRANCH" != "main" ]; then
    echo "Warning: Not on main branch. Current branch: $CURRENT_BRANCH"
    read -p "Continue anyway? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# Verify working directory is clean
if [ -n "$(git status --porcelain)" ]; then
    echo "Error: Working directory is not clean. Please commit or stash changes."
    git status --short
    exit 1
fi

# Verify GPG setup
echo "Checking GPG configuration..."
if ! gpg --list-secret-keys | grep -q "sec"; then
    echo "Error: No GPG secret keys found. Please set up GPG signing."
    echo "See: https://central.sonatype.org/publish/requirements/gpg/"
    exit 1
fi

# Run tests for publishable modules
echo ""
echo "Running tests..."
mvn test -pl "$MODULE_LIST" -am -q

# Build and verify artifacts
echo ""
echo "Building release artifacts..."
mvn clean package -pl "$MODULE_LIST" -am -DperformRelease=true -DskipTests -q

# Deploy to staging repository
echo ""
echo "Deploying to Maven Central..."
mvn deploy -pl "$MODULE_LIST" -am -DperformRelease=true -DskipTests

echo ""
echo "=========================================="
echo "Release deployed to Maven Central!"
echo "=========================================="
echo ""
echo "Next steps:"
echo "1. Log into https://central.sonatype.com/"
echo "2. Go to 'Deployments'"
echo "3. Find your deployment"
echo "4. Click 'Publish' to release to Maven Central"
echo ""
echo "For more information, see:"
echo "https://central.sonatype.org/publish/release/"
