#!/bin/sh
# Install Aether from pre-built distribution archives.
#
# Self-contained archives (recommended, no JDK required):
#   curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/aether/install.sh | sh
#
# Fallback: if platform-specific archives are not available, downloads JARs
# and creates wrapper scripts that require a local JDK 25+ installation.
#
# Options:
#   --version VERSION    Install specific version (default: latest)
#   --jar-only           Force JAR-only install (skip archive detection)
#
# Environment:
#   AETHER_HOME          Install directory (default: ~/.aether)
#   VERSION              Version to install (alternative to --version flag)

set -e

REPO="pragmaticalabs/pragmatica"
INSTALL_DIR="${AETHER_HOME:-$HOME/.aether}"
JAR_ONLY=0

main() {
    detect_platform
    get_latest_version
    if [ "$JAR_ONLY" = "0" ] && try_archive_install; then
        setup_path
        print_success
    else
        check_java
        jar_install
        setup_path
        print_success
    fi
}

detect_platform() {
    OS="$(uname -s)"
    ARCH="$(uname -m)"

    case "$OS" in
        Linux*)  PLATFORM_OS="linux" ;;
        Darwin*) PLATFORM_OS="darwin" ;;
        MINGW*|MSYS*|CYGWIN*)
            echo "Windows detected. Aether requires WSL2 for Windows."
            echo "Install WSL2: https://learn.microsoft.com/en-us/windows/wsl/install"
            exit 1
            ;;
        *)       echo "Unsupported OS: $OS"; exit 1 ;;
    esac

    case "$ARCH" in
        x86_64|amd64)  PLATFORM_ARCH="amd64" ;;
        aarch64|arm64) PLATFORM_ARCH="arm64" ;;
        *)             echo "Unsupported architecture: $ARCH"; exit 1 ;;
    esac

    PLATFORM="${PLATFORM_OS}-${PLATFORM_ARCH}"
}

check_java() {
    if ! command -v java >/dev/null 2>&1; then
        echo "Error: Java is not installed and no platform archive available."
        echo "Either install JDK 25+ or use a platform-specific distribution archive."
        exit 1
    fi
}

get_latest_version() {
    if [ -n "${VERSION:-}" ]; then
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

# --- Self-contained archive install (includes bundled JRE) ---

try_archive_install() {
    BASE_URL="https://github.com/$REPO/releases/download/v$VERSION"

    # Check if platform archives exist for this release
    echo "Checking for self-contained archives ($PLATFORM)..."

    NODE_ARCHIVE="aether-node-${VERSION}-${PLATFORM}.tar.gz"
    CLI_ARCHIVE="aether-cli-${VERSION}-${PLATFORM}.tar.gz"
    FORGE_ARCHIVE="aether-forge-${VERSION}-${PLATFORM}.tar.gz"

    # Test if the node archive exists (HEAD request)
    if ! curl -fsSL --head "$BASE_URL/$NODE_ARCHIVE" >/dev/null 2>&1; then
        echo "  Platform archives not available. Falling back to JAR install."
        return 1
    fi

    echo "  Found platform archives. Downloading..."
    TEMP_DIR=$(mktemp -d)

    # Download and verify checksums
    curl -fsSL "$BASE_URL/SHA256SUMS" -o "$TEMP_DIR/SHA256SUMS" 2>/dev/null || true

    for archive in "$NODE_ARCHIVE" "$CLI_ARCHIVE" "$FORGE_ARCHIVE"; do
        echo "  Downloading $archive..."
        if ! curl -fsSL "$BASE_URL/$archive" -o "$TEMP_DIR/$archive"; then
            echo "  Warning: Failed to download $archive, skipping."
            continue
        fi

        # Verify checksum if available
        if [ -f "$TEMP_DIR/SHA256SUMS" ]; then
            expected=$(grep "$archive" "$TEMP_DIR/SHA256SUMS" | awk '{print $1}')
            if [ -n "$expected" ]; then
                actual=$(sha256sum "$TEMP_DIR/$archive" 2>/dev/null || shasum -a 256 "$TEMP_DIR/$archive" 2>/dev/null)
                actual=$(echo "$actual" | awk '{print $1}')
                if [ "$expected" != "$actual" ]; then
                    echo "  Error: Checksum mismatch for $archive"
                    rm -rf "$TEMP_DIR"
                    exit 1
                fi
            fi
        fi
    done

    echo "  Extracting..."
    rm -rf "$INSTALL_DIR"
    mkdir -p "$INSTALL_DIR/bin"

    # Extract each archive and symlink launchers into a unified bin/
    for archive in "$NODE_ARCHIVE" "$CLI_ARCHIVE" "$FORGE_ARCHIVE"; do
        if [ -f "$TEMP_DIR/$archive" ]; then
            tar -xzf "$TEMP_DIR/$archive" -C "$INSTALL_DIR"
        fi
    done

    # Create unified bin/ with symlinks
    NODE_DIR="$INSTALL_DIR/aether-node-${VERSION}"
    CLI_DIR="$INSTALL_DIR/aether-cli-${VERSION}"
    FORGE_DIR="$INSTALL_DIR/aether-forge-${VERSION}"

    [ -x "$NODE_DIR/bin/aether-node" ]  && ln -sf "$NODE_DIR/bin/aether-node" "$INSTALL_DIR/bin/aether-node"
    [ -x "$CLI_DIR/bin/aether" ]        && ln -sf "$CLI_DIR/bin/aether" "$INSTALL_DIR/bin/aether"
    [ -x "$FORGE_DIR/bin/aether-forge" ] && ln -sf "$FORGE_DIR/bin/aether-forge" "$INSTALL_DIR/bin/aether-forge"

    INSTALL_MODE="archive"
    rm -rf "$TEMP_DIR"
    echo "  Checksums verified."
    return 0
}

# --- JAR-only install (requires local JDK) ---

jar_install() {
    BASE_URL="https://github.com/$REPO/releases/download/v$VERSION"

    echo "Downloading Aether $VERSION (JAR-only, requires JDK)..."
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
exec java -XX:+UseZGC -XX:+ZGenerational \${AETHER_JAVA_OPTS:-} -jar "$INSTALL_DIR/lib/aether-node.jar" "\$@"
WRAPPER

    cat > "$INSTALL_DIR/bin/aether-forge" << WRAPPER
#!/bin/sh
exec java -XX:+UseZGC -XX:+ZGenerational \${AETHER_JAVA_OPTS:-} -jar "$INSTALL_DIR/lib/aether-forge.jar" "\$@"
WRAPPER

    chmod +x "$INSTALL_DIR/bin/aether"
    chmod +x "$INSTALL_DIR/bin/aether-node"
    chmod +x "$INSTALL_DIR/bin/aether-forge"

    INSTALL_MODE="jar"
}

setup_path() {
    BIN_DIR="$INSTALL_DIR/bin"

    # Detect shell config file
    if [ -n "${ZSH_VERSION:-}" ] || [ -f "$HOME/.zshrc" ]; then
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

    if [ "${INSTALL_MODE:-jar}" = "archive" ]; then
        echo "  (self-contained — no JDK required)"
    else
        echo "  (JAR-only — requires JDK 25+)"
    fi

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
        --jar-only)
            JAR_ONLY=1
            shift
            ;;
        --help|-h)
            echo "Usage: install.sh [--version VERSION] [--jar-only]"
            echo ""
            echo "Options:"
            echo "  --version VERSION  Install specific version (default: latest)"
            echo "  --jar-only         Skip archive detection, download JARs only (requires JDK)"
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
