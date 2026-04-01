#!/usr/bin/env bash
set -euo pipefail

# Pragmatica Monorepo Build Script
# Builds all modules in correct order including e2e/forge tests

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Run maven silently, fail on BUILD FAILURE
mvn_quiet() {
    local output
    output=$(mvn "$@" -B 2>&1) || {
        echo "$output" | grep -E '^\[ERROR\]' || true
        echo "$output" | tail -5
        exit 1
    }
}

# Run format/lint — show JBCT warnings and errors, skip javac noise
mvn_lint() {
    local output
    output=$(mvn "$@" -B 2>&1) || {
        echo "$output" | grep -E '^\[ERROR\].*\[JBCT|^\[WARNING\].*Formatted:|^\[WARNING\].*Skipping|^\[ERROR\].*Failed to execute' || true
        echo "$output" | tail -5
        exit 1
    }
    # Show JBCT-relevant warnings even on success
    echo "$output" | grep -E '^\[ERROR\].*\[JBCT|^\[WARNING\].*Formatted:|^\[WARNING\].*Skipping' || true
}

echo "=== Pragmatica Build ==="

# Step 1: Bootstrap annotation processors and Maven plugins
echo ""
echo "Step 1/4: Bootstrap annotation processors and Maven plugins..."
mvn_quiet install -DskipTests -Djbct.skip=true -pl jbct,jbct/slice-processor,aether/pg-tools/pg-codegen -am

# Step 2: Format and lint all non-jbct modules
echo ""
echo "Step 2/4: Format and lint..."
mvn_lint org.pragmatica-lite:jbct-maven-plugin:format org.pragmatica-lite:jbct-maven-plugin:lint -pl '!jbct'

# Step 3: Install all main modules (includes examples)
echo ""
echo "Step 3/4: Install all modules..."
mvn_quiet install -DskipTests

# Step 4: Build e2e and forge tests (compile only)
echo ""
echo "Step 4/4: Build e2e and forge tests..."
mvn_quiet compile test-compile -Pwith-e2e -pl aether/e2e-tests,aether/forge/forge-tests

echo ""
echo "=== Build Complete ==="
echo ""
echo "To run tests:        mvn test"
echo "To run e2e tests:    mvn verify -Pwith-e2e -pl aether/e2e-tests -DskipE2ETests=false"
echo "To run forge tests:  mvn verify -Pwith-e2e -pl aether/forge/forge-tests"
echo "To format/lint only: mvn org.pragmatica-lite:jbct-maven-plugin:format org.pragmatica-lite:jbct-maven-plugin:lint -pl '!jbct'"
