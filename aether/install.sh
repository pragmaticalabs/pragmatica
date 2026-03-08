#!/bin/sh
set -e

REPO="pragmaticalabs/pragmatica"
INSTALL_DIR="${AETHER_HOME:-$HOME/.aether}"

main() {
    check_java
    detect_platform
    get_latest_version
    download_and_install
    setup_path
    print_success
}

check_java() {
    if ! command -v java >/dev/null 2>&1; then
        echo "Error: Java is not installed. Please install JDK 25+ first."
        exit 1
    fi
}

detect_platform() {
    OS="$(uname -s)"
    case "$OS" in
        Linux*)  PLATFORM="linux" ;;
        Darwin*) PLATFORM="macos" ;;
        MINGW*|MSYS*|CYGWIN*)
            echo "Windows detected. Aether requires WSL2 for Windows."
            echo "Install WSL2: https://learn.microsoft.com/en-us/windows/wsl/install"
            exit 1
            ;;
        *)       echo "Unsupported OS: $OS"; exit 1 ;;
    esac
}

get_latest_version() {
    if [ -n "$VERSION" ]; then
        echo "Using specified version: $VERSION"
        return
    fi
    echo "Fetching latest version..."
    VERSION=$(curl -fsSL "https://api.github.com/repos/$REPO/releases" \
        | grep '"tag_name"' \
        | sed -E 's/.*"v?([^"]+)".*/\1/' \
        | sort -t. -k1,1rn -k2,2rn -k3,3rn \
        | head -1)
    if [ -z "$VERSION" ]; then
        echo "Error: Could not determine latest version"
        exit 1
    fi
    echo "Latest version: $VERSION"
}

download_and_install() {
    BASE_URL="https://github.com/$REPO/releases/download/v$VERSION"

    echo "Downloading Aether $VERSION..."
    mkdir -p "$INSTALL_DIR/lib" "$INSTALL_DIR/bin"

    # Download all three JARs
    echo "  Downloading aether.jar..."
    curl -fsSL "$BASE_URL/aether.jar" -o "$INSTALL_DIR/lib/aether.jar"

    echo "  Downloading aether-node.jar..."
    curl -fsSL "$BASE_URL/aether-node.jar" -o "$INSTALL_DIR/lib/aether-node.jar"

    echo "  Downloading aether-forge.jar..."
    curl -fsSL "$BASE_URL/aether-forge.jar" -o "$INSTALL_DIR/lib/aether-forge.jar"

    # Download and verify checksums
    echo "  Downloading SHA256SUMS..."
    if curl -fsSL "$BASE_URL/SHA256SUMS" -o "$INSTALL_DIR/lib/SHA256SUMS" 2>/dev/null; then
        echo "  Verifying checksums..."
        cd "$INSTALL_DIR/lib"
        # Extract only relevant entries and verify
        for jar in aether.jar aether-node.jar aether-forge.jar; do
            expected=$(grep "$jar" SHA256SUMS | awk '{print $1}')
            if [ -n "$expected" ]; then
                actual=$(sha256sum "$jar" 2>/dev/null || shasum -a 256 "$jar" 2>/dev/null)
                actual=$(echo "$actual" | awk '{print $1}')
                if [ "$expected" != "$actual" ]; then
                    echo "Error: Checksum mismatch for $jar"
                    echo "  Expected: $expected"
                    echo "  Actual:   $actual"
                    exit 1
                fi
            fi
        done
        echo "  Checksums verified."
        rm -f SHA256SUMS
        cd - > /dev/null
    else
        echo "  Warning: SHA256SUMS not available, skipping verification."
    fi

    # Create wrapper scripts
    cat > "$INSTALL_DIR/bin/aether" << WRAPPER
#!/bin/sh
exec java -jar "$INSTALL_DIR/lib/aether.jar" "\$@"
WRAPPER

    cat > "$INSTALL_DIR/bin/aether-node" << WRAPPER
#!/bin/sh
exec java -jar "$INSTALL_DIR/lib/aether-node.jar" "\$@"
WRAPPER

    cat > "$INSTALL_DIR/bin/aether-forge" << WRAPPER
#!/bin/sh
exec java -jar "$INSTALL_DIR/lib/aether-forge.jar" "\$@"
WRAPPER

    chmod +x "$INSTALL_DIR/bin/aether"
    chmod +x "$INSTALL_DIR/bin/aether-node"
    chmod +x "$INSTALL_DIR/bin/aether-forge"
}

setup_path() {
    BIN_DIR="$INSTALL_DIR/bin"

    # Detect shell config file
    if [ -n "$ZSH_VERSION" ] || [ -f "$HOME/.zshrc" ]; then
        SHELL_RC="$HOME/.zshrc"
    elif [ -f "$HOME/.bashrc" ]; then
        SHELL_RC="$HOME/.bashrc"
    elif [ -f "$HOME/.bash_profile" ]; then
        SHELL_RC="$HOME/.bash_profile"
    else
        SHELL_RC=""
    fi

    # Check if already in PATH
    case ":$PATH:" in
        *":$BIN_DIR:"*) PATH_CONFIGURED=1 ;;
        *) PATH_CONFIGURED=0 ;;
    esac

    if [ "$PATH_CONFIGURED" = "0" ] && [ -n "$SHELL_RC" ]; then
        if ! grep -q "AETHER" "$SHELL_RC" 2>/dev/null; then
            echo "" >> "$SHELL_RC"
            echo "# Aether" >> "$SHELL_RC"
            echo "export PATH=\"\$HOME/.aether/bin:\$PATH\"" >> "$SHELL_RC"
            ADDED_TO_RC=1
        fi
    fi
}

print_success() {
    echo ""
    echo "Aether $VERSION installed to $INSTALL_DIR"
    echo ""
    echo "Installed tools:"
    echo "  aether        - Cluster management CLI"
    echo "  aether-node   - Run a cluster node"
    echo "  aether-forge  - Testing simulator with dashboard"
    echo ""

    if [ "$PATH_CONFIGURED" = "0" ]; then
        if [ "${ADDED_TO_RC:-0}" = "1" ]; then
            echo "PATH updated in $SHELL_RC"
            echo "Run: source $SHELL_RC"
        else
            echo "Add to your PATH:"
            echo "  export PATH=\"\$HOME/.aether/bin:\$PATH\""
        fi
        echo ""
    fi

    echo "Quick start:"
    echo "  aether --help       # CLI help"
    echo "  aether-forge        # Start simulator at http://localhost:8888"
}

# Parse arguments
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
        --help|-h)
            echo "Usage: install.sh [--version VERSION]"
            echo ""
            echo "Options:"
            echo "  --version VERSION  Install specific version (default: latest)"
            echo ""
            echo "Environment:"
            echo "  AETHER_HOME        Install directory (default: ~/.aether)"
            echo "  VERSION            Version to install (alternative to --version flag)"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

main
