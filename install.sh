#!/bin/sh
# Unified installer for JBCT + Aether tools
# Usage: curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/install.sh | sh
set -e

REPO_BASE="https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main"

echo "Installing Pragmatica development tools..."
echo ""

# Install JBCT CLI
echo "=== Installing JBCT CLI ==="
curl -fsSL "$REPO_BASE/jbct/install.sh" | sh

echo ""

# Install Aether tools
echo "=== Installing Aether tools ==="
curl -fsSL "$REPO_BASE/aether/install.sh" | sh

echo ""
echo "========================================="
echo "  All tools installed successfully!"
echo "========================================="
echo ""
echo "Quick start:"
echo "  jbct init my-slice"
echo "  cd my-slice"
echo "  ./run-forge.sh"
echo "  curl http://localhost:8070/api/hello/World"
echo ""
