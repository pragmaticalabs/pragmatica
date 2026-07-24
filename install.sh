#!/bin/sh
# Unified installer for JBCT + Aether tools
# Usage: curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/install.sh | sh
#
# Piped installs cannot receive arguments directly — pass them after `sh -s --`:
#   curl -fsSL .../install.sh | sh -s -- --version 1.0.0-rc2
#
# Options:
#   --version VERSION          Version to install for both tools (default: latest)
#   --jbct-version VERSION     Version to install for JBCT (overrides --version)
#   --aether-version VERSION   Version to install for Aether (overrides --version)
#
# JBCT and Aether are versioned independently, so the two per-tool flags let
# them diverge; the shared --version only sets a default for whichever
# per-tool flag isn't given.
set -e

REPO_BASE="https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main"

VERSION=""
JBCT_VERSION=""
AETHER_VERSION=""

while [ $# -gt 0 ]; do
    case "$1" in
        --version)
            VERSION="$2"
            shift 2
            ;;
        --version=*)
            VERSION="${1#*=}"
            shift
            ;;
        --jbct-version)
            JBCT_VERSION="$2"
            shift 2
            ;;
        --jbct-version=*)
            JBCT_VERSION="${1#*=}"
            shift
            ;;
        --aether-version)
            AETHER_VERSION="$2"
            shift 2
            ;;
        --aether-version=*)
            AETHER_VERSION="${1#*=}"
            shift
            ;;
        --help|-h)
            echo "Usage: install.sh [--version VERSION] [--jbct-version VERSION] [--aether-version VERSION]"
            echo ""
            echo "Options:"
            echo "  --version VERSION          Version to install for both tools (default: latest)"
            echo "  --jbct-version VERSION     Version to install for JBCT (overrides --version)"
            echo "  --aether-version VERSION   Version to install for Aether (overrides --version)"
            echo ""
            echo "Piped installs cannot receive arguments directly; use:"
            echo "  curl -fsSL .../install.sh | sh -s -- --version VERSION"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

[ -z "$JBCT_VERSION" ] && JBCT_VERSION="$VERSION"
[ -z "$AETHER_VERSION" ] && AETHER_VERSION="$VERSION"

echo "Installing Pragmatica development tools..."
echo ""

# Install JBCT CLI
echo "=== Installing JBCT CLI ==="
if [ -n "$JBCT_VERSION" ]; then
    curl -fsSL "$REPO_BASE/jbct/install.sh" | sh -s -- --version "$JBCT_VERSION"
else
    curl -fsSL "$REPO_BASE/jbct/install.sh" | sh
fi

echo ""

# Install Aether tools
echo "=== Installing Aether tools ==="
if [ -n "$AETHER_VERSION" ]; then
    curl -fsSL "$REPO_BASE/aether/install.sh" | sh -s -- --version "$AETHER_VERSION"
else
    curl -fsSL "$REPO_BASE/aether/install.sh" | sh
fi

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
