#!/usr/bin/env bash
set -euo pipefail

# Pragmatica Monorepo Build Script
# Builds all modules in correct order including e2e/forge tests

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Cluster secret for deterministic QUIC TLS during development builds.
# Real deployments supply this via the operator's environment.
: "${AETHER_CLUSTER_SECRET:=aether-local-dev-cluster-secret}"
export AETHER_CLUSTER_SECRET

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
echo "Step 1/6: Bootstrap annotation processors and Maven plugins..."
mvn_quiet install -DskipTests -Djbct.skip=true -pl jbct/jbct-maven-plugin,jbct/slice-processor,aether/pg-tools/pg-codegen -am

# Step 2: Format + lint all non-jbct modules — the JBCT gate.
# Format RE-ENABLED 2026-06-10 (PR #243): orphan-trivia sweep eliminated the
# comment-deletion bug — idempotent, 0 comment deletions across 2667 files.
# Lint gate RE-ENABLED 2026-06-13: the pre-existing JBCT lint-error debt (core +
# integrations + aether; cleared via @Contract for interface/framework-forced signatures
# plus genuine Result/Promise-propagation refactors) is at 0, so the combined `process`
# goal (format + lint, fail on lint ERRORS) is restored. Warnings are non-fatal
# (jbct.toml failOnWarning=false) and burned down routinely. jbct is excluded — its
# linter (jbct-core) depends on core, a bootstrap cycle; jbct self-lints in its own build.
echo ""
echo "Step 2/6: Format + lint (JBCT gate)..."
mvn_lint org.pragmatica-lite:jbct-maven-plugin:process -pl '!jbct'

# Step 3: Install all main modules (includes examples)
echo ""
echo "Step 3/6: Install all modules..."
mvn_quiet install -DskipTests

# Step 4: Build e2e and forge tests (compile only)
echo ""
echo "Step 4/6: Build e2e and forge tests..."
mvn_quiet compile test-compile -Pwith-e2e -pl aether/e2e-tests,aether/forge/forge-tests

# Step 5: Build test blueprints
echo ""
echo "Step 5/6: Build test blueprints..."
for bp in aether/tests/blueprints/test-echo aether/tests/blueprints/test-persistence aether/tests/blueprints/test-full aether/tests/blueprints/test-stream; do
    mvn_quiet -f "$bp/pom.xml" install -DskipTests
done

# Step 6: Lint integration test infra (ratchet against baseline)
# See aether/docs/internal/audits/integration-test-audit-2026-05-21.md §2.2 and
# aether/tests/integration/lint-baseline.txt for known-issue list.
echo ""
echo "Step 6/6: Lint integration tests..."
./aether/tests/integration/lint-tests.sh

echo ""
echo "=== Build Complete ==="
echo ""
echo "To run tests:        mvn test"
echo "To run e2e tests:    mvn verify -Pwith-e2e -pl aether/e2e-tests -DskipE2ETests=false"
echo "To run forge tests:  mvn verify -Pwith-e2e -pl aether/forge/forge-tests"
echo "To format/lint only: mvn org.pragmatica-lite:jbct-maven-plugin:format org.pragmatica-lite:jbct-maven-plugin:lint -pl '!jbct'"
