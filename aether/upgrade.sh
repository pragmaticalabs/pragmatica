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

# Resolve the version tag to install for an unpinned ("latest") request.
# Reads newline-separated tag names (without a leading "v") from stdin and
# prints the tag ranked highest by: major.minor.patch (numeric), then
# maturity class GA > rc-N > beta > alpha (numeric-aware N, so rc10 > rc9).
# Tags matching *-candidate are always excluded — the candidate tag is a
# moving pre-release marker, never an installable release. Tags that don't
# parse as MAJOR.MINOR.PATCH[-suffix] are skipped. Prints nothing if no tag
# qualifies.
resolve_latest_tag() {
    while IFS= read -r tag; do
        [ -n "$tag" ] || continue
        case "$tag" in
            *-candidate) continue ;;
        esac

        core="${tag%%-*}"
        case "$tag" in
            *-*) suffix="${tag#*-}" ;;
            *)   suffix="" ;;
        esac

        major=$(echo "$core" | cut -d. -f1)
        minor=$(echo "$core" | cut -d. -f2)
        patch=$(echo "$core" | cut -d. -f3)
        case "$major" in ''|*[!0-9]*) continue ;; esac
        case "$minor" in ''|*[!0-9]*) continue ;; esac
        case "$patch" in ''|*[!0-9]*) continue ;; esac

        case "$suffix" in
            "")     class=4; num=0 ;;
            rc*)    class=3; num="${suffix#rc}" ;;
            beta*)  class=2; num="${suffix#beta}" ;;
            alpha*) class=1; num="${suffix#alpha}" ;;
            *)      class=0; num=0 ;;
        esac
        case "$num" in ''|*[!0-9]*) num=0 ;; esac

        printf '%05d.%05d.%05d.%d.%05d\t%s\n' "$major" "$minor" "$patch" "$class" "$num" "$tag"
    done | LC_ALL=C sort -r | head -1 | cut -f2
}

# Confirm a specific version exists as a published release (not just any
# tag), so an explicitly requested version fails loudly up front instead of
# falling through to a download 404 buried inside the upgrade steps.
verify_version_exists() {
    version="$1"
    code=$(curl -s -o /dev/null -w '%{http_code}' "https://api.github.com/repos/$REPO/releases/tags/v$version")
    [ "$code" = "200" ]
}

determine_target_version() {
    if [ -n "$TARGET_VERSION" ]; then
        echo "Target version: $TARGET_VERSION"
        if ! verify_version_exists "$TARGET_VERSION"; then
            echo "Error: version 'v$TARGET_VERSION' not found in $REPO releases."
            echo "  See: https://github.com/$REPO/releases"
            exit 1
        fi
        return
    fi
    echo "Fetching latest version..."
    TARGET_VERSION=$(curl -fsSL "https://api.github.com/repos/$REPO/releases" \
        | grep '"tag_name"' \
        | sed -E 's/.*"v?([^"]+)".*/\1/' \
        | resolve_latest_tag)
    if [ -z "$TARGET_VERSION" ]; then
        echo "Error: could not determine latest version (no non-candidate releases found)"
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
    # Remove old versioned directories (any prior archive-mode generation)
    for component in aether-node aether-cli aether-forge; do
        rm -rf "$INSTALL_DIR"/${component}-*/
    done

    # Remove stale jar-mode remnants so no old jar wrapper survives a
    # jar -> archive transition.
    rm -rf "$INSTALL_DIR/lib"
    rm -f "$INSTALL_DIR/bin/aether" "$INSTALL_DIR/bin/aether-node" "$INSTALL_DIR/bin/aether-forge"

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

    for bin in aether aether-node aether-forge; do
        [ -e "$INSTALL_DIR/bin/$bin" ] || echo "  Warning: $bin archive not available for $TARGET_VERSION; launcher not installed."
    done

    rm -rf "$TEMP_DIR"
}

swap_jars() {
    # Remove stale archive-mode remnants so no versioned dist dir or its
    # bin/ symlink can shadow the jar-mode launchers written here.
    for component in aether-node aether-cli aether-forge; do
        rm -rf "$INSTALL_DIR"/${component}-*/
    done

    mkdir -p "$INSTALL_DIR/lib" "$INSTALL_DIR/bin"

    # Move new JARs into place; any jar of any prior generation is simply
    # overwritten, so no partial state survives.
    for jar in aether.jar aether-node.jar aether-forge.jar; do
        rm -f "$INSTALL_DIR/lib/$jar"
        mv "$TEMP_DIR/$jar" "$INSTALL_DIR/lib/$jar"
    done

    # Regenerate launcher wrappers unconditionally so a stale bin/ entry
    # from a prior generation (symlink, archive-mode launcher, hand edit)
    # never survives an upgrade.
    write_jar_wrappers

    rm -rf "$TEMP_DIR"
}

# Create wrapper scripts for jar-mode launchers. Each wrapper validates its
# target jar exists before exec, so a partial or stale install fails with an
# actionable message instead of a bare "Unable to access jarfile".
write_jar_wrappers() {
    # rm -f first: bin/aether* may currently be a symlink left by a prior
    # archive-mode install (possibly dangling). "cat >" writes THROUGH a
    # symlink instead of replacing it, which would either fail outright or
    # silently clobber whatever the symlink still points at. Removing the
    # entry guarantees each wrapper below is always a fresh regular file.
    rm -f "$INSTALL_DIR/bin/aether" "$INSTALL_DIR/bin/aether-node" "$INSTALL_DIR/bin/aether-forge"

    cat > "$INSTALL_DIR/bin/aether" << WRAPPER
#!/bin/sh
if [ ! -f "$INSTALL_DIR/lib/aether.jar" ]; then
    echo "Error: $INSTALL_DIR/lib/aether.jar not found." >&2
    echo "The installation is incomplete or corrupted. Run install.sh again." >&2
    exit 1
fi
exec java -jar "$INSTALL_DIR/lib/aether.jar" "\$@"
WRAPPER

    cat > "$INSTALL_DIR/bin/aether-node" << WRAPPER
#!/bin/sh
if [ ! -f "$INSTALL_DIR/lib/aether-node.jar" ]; then
    echo "Error: $INSTALL_DIR/lib/aether-node.jar not found." >&2
    echo "The installation is incomplete or corrupted. Run install.sh again." >&2
    exit 1
fi
exec java -XX:+UseZGC \${AETHER_JAVA_OPTS:-} -jar "$INSTALL_DIR/lib/aether-node.jar" "\$@"
WRAPPER

    cat > "$INSTALL_DIR/bin/aether-forge" << WRAPPER
#!/bin/sh
if [ ! -f "$INSTALL_DIR/lib/aether-forge.jar" ]; then
    echo "Error: $INSTALL_DIR/lib/aether-forge.jar not found." >&2
    echo "The installation is incomplete or corrupted. Run install.sh again." >&2
    exit 1
fi
exec java -XX:+UseZGC \${AETHER_JAVA_OPTS:-} -jar "$INSTALL_DIR/lib/aether-forge.jar" "\$@"
WRAPPER

    chmod +x "$INSTALL_DIR/bin/aether" "$INSTALL_DIR/bin/aether-node" "$INSTALL_DIR/bin/aether-forge"
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
