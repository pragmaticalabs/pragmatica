#!/bin/bash
# Build self-contained distribution archives for Aether components.
# Each archive bundles a custom JRE (via jlink) so users need no JDK installed.
#
# Usage: ./build-dist.sh [--version 0.25.0] [--targets node,cli,forge]
#
# For cross-platform builds, set JAVA_HOME to a JDK for the target platform:
#   Linux amd64:  default JDK on Linux x86_64
#   Linux arm64:  set JAVA_HOME to aarch64 JDK
#   macOS amd64:  set JAVA_HOME to macOS x86_64 JDK
#   macOS arm64:  set JAVA_HOME to macOS aarch64 JDK

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
AETHER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
VERSION=""
TARGETS="node,cli,forge"

# --- Argument parsing ---

print_usage() {
    echo "Usage: $0 [--version VERSION] [--targets node,cli,forge]"
    echo ""
    echo "Options:"
    echo "  --version VERSION   Version string for archive names (default: from pom.xml)"
    echo "  --targets TARGETS   Comma-separated list: node,cli,forge (default: all)"
    echo "  --help              Show this help"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --version)   VERSION="$2"; shift 2 ;;
        --version=*) VERSION="${1#*=}"; shift ;;
        --targets)   TARGETS="$2"; shift 2 ;;
        --targets=*) TARGETS="${1#*=}"; shift ;;
        --help|-h)   print_usage; exit 0 ;;
        *)           echo "Unknown option: $1"; print_usage; exit 1 ;;
    esac
done

# --- Detect version from pom.xml if not specified ---

if [ -z "$VERSION" ]; then
    VERSION=$(grep -m1 '<version>' "$AETHER_DIR/pom.xml" | sed 's/.*<version>//;s/<\/version>.*//' | tr -d '[:space:]')
    if [ -z "$VERSION" ]; then
        echo "Error: Could not detect version from pom.xml. Use --version."
        exit 1
    fi
fi

# --- Detect platform ---

detect_platform() {
    local os arch
    os="$(uname -s)"
    arch="$(uname -m)"

    case "$os" in
        Linux)  os="linux" ;;
        Darwin) os="darwin" ;;
        *)      echo "Unsupported OS: $os"; exit 1 ;;
    esac

    case "$arch" in
        x86_64|amd64)  arch="amd64" ;;
        aarch64|arm64) arch="arm64" ;;
        *)             echo "Unsupported architecture: $arch"; exit 1 ;;
    esac

    PLATFORM="${os}-${arch}"
}

detect_platform

# --- Resolve JAVA_HOME ---

if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")" 2>/dev/null || true
fi

if [ ! -x "${JAVA_HOME}/bin/jlink" ]; then
    echo "Error: jlink not found in JAVA_HOME ($JAVA_HOME)."
    echo "Set JAVA_HOME to a JDK 25+ installation."
    exit 1
fi

JLINK="${JAVA_HOME}/bin/jlink"
echo "Using JDK: $JAVA_HOME"
echo "Platform:  $PLATFORM"
echo "Version:   $VERSION"
echo "Targets:   $TARGETS"
echo ""

# --- Output directory ---

OUTPUT_DIR="$SCRIPT_DIR/output"
mkdir -p "$OUTPUT_DIR"

# --- jlink modules ---
# Using java.se covers all standard modules. This is intentionally broad
# for Phase 1 to avoid missing-module issues at runtime.
# Optimize with jdeps analysis in a future pass.

JLINK_MODULES="java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.httpserver,jdk.management"

# --- Build custom JRE (shared across targets for same platform) ---

JRE_DIR="$SCRIPT_DIR/output/jre-${PLATFORM}"

build_jre() {
    if [ -d "$JRE_DIR" ]; then
        echo "Reusing existing JRE at $JRE_DIR"
        return
    fi

    echo "Building custom JRE with jlink..."
    "$JLINK" \
        --add-modules "$JLINK_MODULES" \
        --strip-debug \
        --no-man-pages \
        --no-header-files \
        --compress=zip-6 \
        --output "$JRE_DIR"

    echo "JRE size: $(du -sh "$JRE_DIR" | cut -f1)"
    echo ""
}

# --- Build a single component distribution ---

build_component() {
    local name="$1"
    local jar_path="$2"
    local launcher_name="$3"
    local java_opts="$4"
    local readme_extra="${5:-}"

    if [ ! -f "$jar_path" ]; then
        echo "Error: JAR not found: $jar_path"
        echo "Build the project first: mvn package -DskipTests"
        return 1
    fi

    local dist_name="${name}-${VERSION}"
    local dist_dir="$OUTPUT_DIR/$dist_name"

    echo "Building $dist_name..."

    # Clean previous build
    rm -rf "$dist_dir"
    mkdir -p "$dist_dir/bin" "$dist_dir/lib" "$dist_dir/jre"

    # Copy JRE
    cp -r "$JRE_DIR"/* "$dist_dir/jre/"

    # Copy JAR
    cp "$jar_path" "$dist_dir/lib/${name}.jar"

    # Create launcher script
    create_launcher "$dist_dir/bin/$launcher_name" "$name" "$java_opts"

    # Create README
    create_readme "$dist_dir/README.txt" "$name" "$launcher_name" "$readme_extra"

    # Create archives
    local archive_base="${dist_name}-${PLATFORM}"
    (cd "$OUTPUT_DIR" && tar -czf "${archive_base}.tar.gz" "$dist_name")
    (cd "$OUTPUT_DIR" && zip -qr "${archive_base}.zip" "$dist_name")

    local tar_size
    tar_size=$(du -sh "$OUTPUT_DIR/${archive_base}.tar.gz" | cut -f1)
    echo "  Created: ${archive_base}.tar.gz ($tar_size)"
    echo "  Created: ${archive_base}.zip"
    echo ""
}

# --- Create launcher script ---

create_launcher() {
    local launcher_path="$1"
    local jar_name="$2"
    local java_opts="$3"

    cat > "$launcher_path" << LAUNCHER
#!/bin/bash
# Aether ${jar_name} launcher
# This script uses the bundled JRE — no JDK installation required.

SCRIPT_DIR="\$(cd "\$(dirname "\$0")/.." && pwd)"
JAVA="\$SCRIPT_DIR/jre/bin/java"

if [ ! -x "\$JAVA" ]; then
    echo "Error: Bundled JRE not found at \$SCRIPT_DIR/jre"
    echo "The distribution archive may be incomplete."
    exit 1
fi

exec "\$JAVA" \\
    ${java_opts} \\
    \${AETHER_JAVA_OPTS:-} \\
    -jar "\$SCRIPT_DIR/lib/${jar_name}.jar" "\$@"
LAUNCHER
    chmod +x "$launcher_path"
}

# --- Create README ---

create_readme() {
    local readme_path="$1"
    local name="$2"
    local launcher_name="$3"
    local extra="$4"

    cat > "$readme_path" << README
${name} ${VERSION}
$( printf '=%.0s' $(seq 1 $(( ${#name} + ${#VERSION} + 1 ))) )

Self-contained distribution — no JDK installation required.

Quick Start
-----------
    ./${launcher_name}          # Run with defaults
    ./${launcher_name} --help   # Show available options

Directory Layout
----------------
    bin/    Launcher script
    jre/    Bundled Java runtime (JRE 25)
    lib/    Application JAR

Environment Variables
---------------------
    AETHER_JAVA_OPTS    Additional JVM options (appended to defaults)

${extra}
README
}

# --- Build each target ---

build_jre

if echo "$TARGETS" | grep -q "node"; then
    build_component \
        "aether-node" \
        "$AETHER_DIR/node/target/aether-node.jar" \
        "aether-node" \
        "-XX:+UseZGC -XX:+ZGenerational -Xmx512m" \
        "Ports
-----
    8090/udp    Cluster communication (QUIC)
    8190/udp    SWIM health detection
    8080        Management API and dashboard"
fi

if echo "$TARGETS" | grep -q "cli"; then
    build_component \
        "aether-cli" \
        "$AETHER_DIR/cli/target/aether.jar" \
        "aether" \
        "-XX:+UseZGC -XX:+ZGenerational -Xmx256m" \
        "The Aether CLI manages clusters, deployments, and configuration."
fi

if echo "$TARGETS" | grep -q "forge"; then
    build_component \
        "aether-forge" \
        "$AETHER_DIR/forge/forge-core/target/aether-forge.jar" \
        "aether-forge" \
        "-XX:+UseZGC -XX:+ZGenerational -Xmx1g" \
        "Forge is the Aether testing simulator with a visual dashboard.

Default port: 8888
Dashboard:    http://localhost:8888"
fi

# --- Generate checksums ---

echo "Generating checksums..."
(cd "$OUTPUT_DIR" && sha256sum *.tar.gz *.zip 2>/dev/null > SHA256SUMS || shasum -a 256 *.tar.gz *.zip > SHA256SUMS)
echo ""

echo "All archives written to: $OUTPUT_DIR"
echo ""
echo "Contents:"
ls -lh "$OUTPUT_DIR"/*.tar.gz "$OUTPUT_DIR"/*.zip "$OUTPUT_DIR"/SHA256SUMS 2>/dev/null
