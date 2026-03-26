#!/bin/sh
# Upgrade Aether to the latest (or specified) version.
#
# Detects install mode (self-contained archive vs JAR-only) and upgrades accordingly.
#
# Usage: upgrade.sh [--version VERSION]

set -e

REPO="pragmaticalabs/pragmatica"
INSTALL_DIR="${AETHER_HOME:-$HOME/.aether}"

main() {
    check_installation
    detect_current_version
    detect_install_mode
    determine_target_version
    check_version_change
    download_new_version
    verify_checksums
    swap_binaries
    check_running_processes
    print_summary
}

check_installation() {
    if [ ! -d "$INSTALL_DIR" ]; then
        echo "Error: Aether not found at $INSTALL_DIR"
        echo "Run install.sh first."
        exit 1
    fi
}

detect_current_version() {
    if command -v aether >/dev/null 2>&1; then
        CURRENT_VERSION=$(aether --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1) || true
    fi
    if [ -z "$CURRENT_VERSION" ]; then
        CURRENT_VERSION="unknown"
    fi
    echo "Current version: $CURRENT_VERSION"
}

detect_install_mode() {
    # Check if this is a self-contained archive install (has jre/ subdirectories)
    if ls -d "$INSTALL_DIR"/aether-node-*/jre 2>/dev/null | head -1 | grep -q jre; then
        INSTALL_MODE="archive"
    elif [ -d "$INSTALL_DIR/lib" ]; then
        INSTALL_MODE="jar"
    else
        echo "Error: Could not detect install mode at $INSTALL_DIR"
        exit 1
    fi
    echo "Install mode: $INSTALL_MODE"
}

determine_target_version() {
    if [ -n "$TARGET_VERSION" ]; then
        echo "Target version: $TARGET_VERSION"
        return
    fi
    echo "Fetching latest version..."
    TARGET_VERSION=$(curl -fsSL "https://api.github.com/repos/$REPO/releases" \
        | grep '"tag_name"' \
        | sed -E 's/.*"v?([^"]+)".*/\1/' \
        | sort -t. -k1,1rn -k2,2rn -k3,3rn \
        | head -1)
    if [ -z "$TARGET_VERSION" ]; then
        echo "Error: Could not determine latest version"
        exit 1
    fi
    echo "Latest version: $TARGET_VERSION"
}

check_version_change() {
    if [ "$CURRENT_VERSION" = "$TARGET_VERSION" ]; then
        echo "Already at version $TARGET_VERSION. Nothing to do."
        exit 0
    fi
}

download_new_version() {
    BASE_URL="https://github.com/$REPO/releases/download/v$TARGET_VERSION"
    TEMP_DIR=$(mktemp -d)

    if [ "$INSTALL_MODE" = "archive" ]; then
        download_archives
    else
        download_jars
    fi
}

download_archives() {
    # Detect platform
    OS="$(uname -s)"
    ARCH="$(uname -m)"

    case "$OS" in
        Linux*)  PLATFORM_OS="linux" ;;
        Darwin*) PLATFORM_OS="darwin" ;;
        *)       echo "Unsupported OS: $OS"; exit 1 ;;
    esac

    case "$ARCH" in
        x86_64|amd64)  PLATFORM_ARCH="amd64" ;;
        aarch64|arm64) PLATFORM_ARCH="arm64" ;;
        *)             echo "Unsupported architecture: $ARCH"; exit 1 ;;
    esac

    PLATFORM="${PLATFORM_OS}-${PLATFORM_ARCH}"

    echo "Downloading Aether $TARGET_VERSION archives ($PLATFORM)..."

    # Download checksums
    curl -fsSL "$BASE_URL/SHA256SUMS" -o "$TEMP_DIR/SHA256SUMS" 2>/dev/null || true

    for component in aether-node aether-cli aether-forge; do
        archive="${component}-${TARGET_VERSION}-${PLATFORM}.tar.gz"
        echo "  Downloading $archive..."
        if ! curl -fsSL "$BASE_URL/$archive" -o "$TEMP_DIR/$archive"; then
            echo "  Warning: $archive not available, skipping."
            continue
        fi
    done
}

download_jars() {
    echo "Downloading Aether $TARGET_VERSION JARs..."

    for jar in aether.jar aether-node.jar aether-forge.jar; do
        echo "  Downloading $jar..."
        curl -fsSL "$BASE_URL/$jar" -o "$TEMP_DIR/$jar"
    done

    # Download checksums
    echo "  Downloading SHA256SUMS..."
    curl -fsSL "$BASE_URL/SHA256SUMS" -o "$TEMP_DIR/SHA256SUMS" 2>/dev/null || true
}

verify_checksums() {
    if [ ! -f "$TEMP_DIR/SHA256SUMS" ]; then
        echo "  Warning: SHA256SUMS not available, skipping verification."
        return
    fi

    echo "  Verifying checksums..."
    cd "$TEMP_DIR"
    for file in *.tar.gz *.jar; do
        [ -f "$file" ] || continue
        expected=$(grep "$file" SHA256SUMS | awk '{print $1}')
        if [ -n "$expected" ]; then
            actual=$(sha256sum "$file" 2>/dev/null || shasum -a 256 "$file" 2>/dev/null)
            actual=$(echo "$actual" | awk '{print $1}')
            if [ "$expected" != "$actual" ]; then
                echo "Error: Checksum mismatch for $file"
                echo "  Expected: $expected"
                echo "  Actual:   $actual"
                rm -rf "$TEMP_DIR"
                exit 1
            fi
        fi
    done
    echo "  Checksums verified."
    cd - > /dev/null
}

swap_binaries() {
    echo "Upgrading binaries..."

    if [ "$INSTALL_MODE" = "archive" ]; then
        swap_archives
    else
        swap_jars
    fi
}

swap_archives() {
    # Remove old versioned directories
    for component in aether-node aether-cli aether-forge; do
        rm -rf "$INSTALL_DIR"/${component}-*/
    done

    # Extract new archives
    for archive in "$TEMP_DIR"/*.tar.gz; do
        [ -f "$archive" ] || continue
        tar -xzf "$archive" -C "$INSTALL_DIR"
    done

    # Recreate unified bin/ symlinks
    mkdir -p "$INSTALL_DIR/bin"

    NODE_DIR="$INSTALL_DIR/aether-node-${TARGET_VERSION}"
    CLI_DIR="$INSTALL_DIR/aether-cli-${TARGET_VERSION}"
    FORGE_DIR="$INSTALL_DIR/aether-forge-${TARGET_VERSION}"

    [ -x "$NODE_DIR/bin/aether-node" ]   && ln -sf "$NODE_DIR/bin/aether-node" "$INSTALL_DIR/bin/aether-node"
    [ -x "$CLI_DIR/bin/aether" ]         && ln -sf "$CLI_DIR/bin/aether" "$INSTALL_DIR/bin/aether"
    [ -x "$FORGE_DIR/bin/aether-forge" ] && ln -sf "$FORGE_DIR/bin/aether-forge" "$INSTALL_DIR/bin/aether-forge"

    rm -rf "$TEMP_DIR"
}

swap_jars() {
    # Backup existing JARs
    for jar in aether.jar aether-node.jar aether-forge.jar; do
        if [ -f "$INSTALL_DIR/lib/$jar" ]; then
            mv "$INSTALL_DIR/lib/$jar" "$INSTALL_DIR/lib/$jar.bak"
        fi
    done

    # Move new JARs into place
    for jar in aether.jar aether-node.jar aether-forge.jar; do
        mv "$TEMP_DIR/$jar" "$INSTALL_DIR/lib/$jar"
    done

    # Clean up backups and temp dir
    rm -f "$INSTALL_DIR/lib/"*.bak
    rm -rf "$TEMP_DIR"
}

check_running_processes() {
    if pgrep -f "aether-node.jar" > /dev/null 2>&1 || pgrep -f "aether-forge.jar" > /dev/null 2>&1; then
        echo ""
        echo "WARNING: Running Aether processes detected."
        echo "Restart them to use the new version."
    fi
}

print_summary() {
    echo ""
    echo "Upgrade complete: $CURRENT_VERSION -> $TARGET_VERSION"
    echo ""
    echo "Verify: aether --version"
}

# Parse arguments
TARGET_VERSION=""
while [ $# -gt 0 ]; do
    case "$1" in
        --version)
            TARGET_VERSION="$2"
            shift 2
            ;;
        --version=*)
            TARGET_VERSION="${1#*=}"
            shift
            ;;
        --help|-h)
            echo "Usage: upgrade.sh [--version VERSION]"
            echo ""
            echo "Upgrades Aether to the latest (or specified) version."
            echo "Automatically detects install mode (self-contained archive vs JAR-only)."
            echo ""
            echo "Options:"
            echo "  --version VERSION  Upgrade to specific version (default: latest)"
            echo ""
            echo "Environment:"
            echo "  AETHER_HOME        Install directory (default: ~/.aether)"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

main
