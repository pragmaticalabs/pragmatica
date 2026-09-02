#!/bin/sh
set -e

REPO="pragmaticalabs/pragmatica"
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
# falling through to a download 404 buried inside the install steps.
verify_version_exists() {
    version="$1"
    code=$(curl -s -o /dev/null -w '%{http_code}' "https://api.github.com/repos/$REPO/releases/tags/v$version")
    [ "$code" = "200" ]
}

get_latest_version() {
    if [ -n "$VERSION" ]; then
        echo "Using specified version: $VERSION"
        if ! verify_version_exists "$VERSION"; then
            echo "Error: version 'v$VERSION' not found in $REPO releases."
            echo "  See: https://github.com/$REPO/releases"
            exit 1
        fi
        return
    fi
    echo "Fetching latest version..."
    VERSION=$(curl -fsSL "https://api.github.com/repos/$REPO/releases" \
        | grep '"tag_name"' \
        | sed -E 's/.*"v?([^"]+)".*/\1/' \
        | resolve_latest_tag)
    if [ -z "$VERSION" ]; then
        echo "Error: could not determine latest version (no non-candidate releases found)"
        exit 1
    fi
    echo "Latest version: $VERSION"
}

download_and_install() {
    DOWNLOAD_URL="https://github.com/$REPO/releases/download/v$VERSION/jbct.jar"

    echo "Downloading JBCT $VERSION..."
    mkdir -p "$INSTALL_DIR/lib" "$INSTALL_DIR/bin"

    curl -fsSL "$DOWNLOAD_URL" -o "$INSTALL_DIR/lib/jbct.jar"

    # Create wrapper script. Validates the jar exists before exec so a
    # partial or stale install fails with an actionable message instead of
    # a bare "Unable to access jarfile". rm -f first: "cat >" writes THROUGH
    # a symlink rather than replacing it, so any pre-existing bin/jbct
    # (e.g. a manual symlink) must be cleared to guarantee a fresh file.
    rm -f "$INSTALL_DIR/bin/jbct"
    cat > "$INSTALL_DIR/bin/jbct" << WRAPPER
#!/bin/sh
if [ ! -f "$INSTALL_DIR/lib/jbct.jar" ]; then
    echo "Error: $INSTALL_DIR/lib/jbct.jar not found." >&2
    echo "The installation is incomplete or corrupted. Run install.sh again." >&2
    exit 1
fi
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
        # Match on the export line's own content (fixed string, not the "#
        # JBCT" comment) so the guard keeps working even if the comment
        # text changes, rather than being coincidentally tied to it.
        if ! grep -qF '.jbct/bin' "$SHELL_RC" 2>/dev/null; then
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
            echo "  JBCT_HOME          Install directory (default: ~/.jbct)"
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
