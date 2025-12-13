#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

# Initialize submodules if needed
git submodule update --init --recursive

# Build JavaParser (install to local .m2)
# Use -Dmaven.compiler.release=8 to work around Java 21+ SequencedCollection incompatibility
# See: https://github.com/javaparser/javaparser/issues/4022
echo "Building JavaParser..."
cd "$PROJECT_DIR/javaparser"
mvn install -DskipTests -Dmaven.compiler.release=8 -q

# Build jbct-cli
echo "Building jbct-cli..."
cd "$PROJECT_DIR"
mvn clean verify "$@"

echo "Build complete."
