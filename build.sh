#!/usr/bin/env bash
set -euo pipefail

# Pragmatica Monorepo Build Script
# Builds all modules in correct order including e2e/forge tests

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Pragmatica Build ==="

# Step 1: Bootstrap jbct-maven-plugin and slice-processor
echo ""
echo "Step 1/3: Bootstrap jbct-maven-plugin and slice-processor..."
mvn install -B -DskipTests -pl jbct/jbct-maven-plugin,jbct/slice-processor -am -q

# Step 2: Install all main modules (includes examples)
echo ""
echo "Step 2/3: Install all modules..."
mvn install -B -DskipTests -q

# Step 3: Build e2e and forge tests (compile only)
echo ""
echo "Step 3/3: Build e2e and forge tests..."
mvn compile test-compile -B -Pwith-e2e -pl aether/e2e-tests,aether/forge/forge-tests -q

echo ""
echo "=== Build Complete ==="
echo ""
echo "To run tests:        mvn test"
echo "To run e2e tests:    mvn verify -Pwith-e2e -pl aether/e2e-tests -DskipE2ETests=false"
echo "To run forge tests:  mvn verify -Pwith-e2e -pl aether/forge/forge-tests"
