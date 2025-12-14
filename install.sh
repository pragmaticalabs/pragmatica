#!/bin/sh
set -e

REPO="siy/jbct-cli"
INSTALL_DIR="${JBCT_HOME:-$HOME/.jbct}"

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
        *)       echo "Unsupported OS: $OS"; exit 1 ;;
    esac
}

get_latest_version() {
    echo "Fetching latest version..."
    VERSION=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | grep '"tag_name"' | sed -E 's/.*"v?([^"]+)".*/\1/')
    if [ -z "$VERSION" ]; then
        echo "Error: Could not determine latest version"
        exit 1
    fi
    echo "Latest version: $VERSION"
}

download_and_install() {
    DOWNLOAD_URL="https://github.com/$REPO/releases/download/v$VERSION/jbct.jar"

    echo "Downloading JBCT $VERSION..."
    mkdir -p "$INSTALL_DIR/lib" "$INSTALL_DIR/bin"

    curl -fsSL "$DOWNLOAD_URL" -o "$INSTALL_DIR/lib/jbct.jar"

    # Create wrapper script
    cat > "$INSTALL_DIR/bin/jbct" << WRAPPER
#!/bin/sh
exec java -jar "$INSTALL_DIR/lib/jbct.jar" "\$@"
WRAPPER

    chmod +x "$INSTALL_DIR/bin/jbct"
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
        if ! grep -q "JBCT" "$SHELL_RC" 2>/dev/null; then
            echo "" >> "$SHELL_RC"
            echo "# JBCT" >> "$SHELL_RC"
            echo "export PATH=\"\$HOME/.jbct/bin:\$PATH\"" >> "$SHELL_RC"
            ADDED_TO_RC=1
        fi
    fi
}

print_success() {
    echo ""
    echo "✓ JBCT $VERSION installed to $INSTALL_DIR"
    echo ""

    if [ "$PATH_CONFIGURED" = "0" ]; then
        if [ "${ADDED_TO_RC:-0}" = "1" ]; then
            echo "PATH updated in $SHELL_RC"
            echo "Run: source $SHELL_RC"
        else
            echo "Add to your PATH:"
            echo "  export PATH=\"\$HOME/.jbct/bin:\$PATH\""
        fi
        echo ""
    fi

    echo "Usage: jbct --help"
}

main
