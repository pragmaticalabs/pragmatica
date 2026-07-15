#!/usr/bin/env bash
#
# version-sweep.sh — fail-loud audit that version-shaped literals on fix-class surfaces track
# the canonical platform version declared in the root pom.xml.
#
# Pure shell + grep/sed/awk (no jq/python/bc, per repo policy). Intended as a pre-release checklist
# step. Designed for testability: the SCAN root is arg $1 (default: repo root), while the
# canonical version is always derived from the repo's own root pom.xml, so a temp directory can be
# scanned in isolation for self-tests.
#
# Surfaces checked (must equal the canonical version):
#   - READMEs (README.md, aether/, core/, jbct/): dependency-snippet <version> lines,
#     Maven-Central shields badge, and the "Release status" banner.
#   - examples/*/pom.xml: <platform.version>.
#   - examples/*/blueprint.toml: trailing artifact-coordinate version.
#   - Java src/main (core/ integrations/ jbct/ aether/): dependency-coordinate <version> tags and
#     pre-release (-rcN) version literals; each must equal the canonical version or carry a
#     "version-literal: <reason>" marker on the same or immediately-preceding line.
#
# Exit: 0 when clean (one-line summary), 1 on any violation, 2 on setup failure.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SCAN_ROOT="${1:-$REPO_ROOT}"

POM="$REPO_ROOT/pom.xml"
if [ ! -f "$POM" ]; then
    echo "version-sweep: root pom.xml not found at $POM" >&2
    exit 2
fi

# Canonical version = first <version> element under the (parent-less) root project element.
CANONICAL="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$POM" | head -1)"
if [ -z "$CANONICAL" ]; then
    echo "version-sweep: could not derive canonical version from $POM" >&2
    exit 2
fi

VERSION_RE='[0-9]+\.[0-9]+\.[0-9]+(-rc[0-9]+)?'
violations=0

report() {
    # $1 file, $2 line, $3 found, $4 optional trailing hint
    printf '%s:%s: found %s expected %s%s\n' "$1" "$2" "$3" "$CANONICAL" "${4:-}"
    violations=$((violations + 1))
}

first_version() {
    printf '%s' "$1" | grep -m1 -oE "$VERSION_RE" || true
}

last_version() {
    printf '%s' "$1" | grep -oE "$VERSION_RE" | tail -1 || true
}

# --- READMEs: <version> tags, Maven-Central badge, Release-status banner ---
check_readme() {
    file="$1"
    [ -f "$file" ] || return 0
    while IFS= read -r hit; do
        lineno="${hit%%:*}"
        content="${hit#*:}"
        found="$(first_version "$content")"
        [ -n "$found" ] || continue
        [ "$found" = "$CANONICAL" ] || report "$file" "$lineno" "$found"
    done < <(grep -nE '<version>|shields\.io/badge/Maven-|Release status' "$file" || true)
}

# --- examples/*/pom.xml: <platform.version> ---
check_example_poms() {
    [ -d "$SCAN_ROOT/examples" ] || return 0
    while IFS= read -r pom; do
        [ -f "$pom" ] || continue
        while IFS= read -r hit; do
            lineno="${hit%%:*}"
            content="${hit#*:}"
            found="$(first_version "$content")"
            [ -n "$found" ] || continue
            [ "$found" = "$CANONICAL" ] || report "$pom" "$lineno" "$found"
        done < <(grep -nE '<platform\.version>' "$pom" || true)
    done < <(find "$SCAN_ROOT/examples" -maxdepth 2 -name pom.xml 2>/dev/null || true)
}

# --- examples/*/blueprint.toml: trailing artifact-coordinate version ---
check_blueprints() {
    [ -d "$SCAN_ROOT/examples" ] || return 0
    while IFS= read -r toml; do
        [ -f "$toml" ] || continue
        while IFS= read -r hit; do
            lineno="${hit%%:*}"
            content="${hit#*:}"
            found="$(last_version "$content")"
            [ -n "$found" ] || continue
            [ "$found" = "$CANONICAL" ] || report "$toml" "$lineno" "$found"
        done < <(grep -nE '^[[:space:]]*artifact[[:space:]]*=' "$toml" || true)
    done < <(find "$SCAN_ROOT/examples" -maxdepth 2 -name blueprint.toml 2>/dev/null || true)
}

# --- Java src/main: dependency-coordinate <version> tags + pre-release literals ---
marked() {
    file="$1"
    lineno="$2"
    start="$lineno"
    [ "$lineno" -gt 1 ] && start=$((lineno - 1))
    sed -n "${start},${lineno}p" "$file" | grep -q 'version-literal:'
}

check_java_file() {
    file="$1"
    while IFS= read -r hit; do
        lineno="${hit%%:*}"
        content="${hit#*:}"
        found="$(first_version "$content")"
        [ -n "$found" ] || continue
        [ "$found" = "$CANONICAL" ] && continue
        marked "$file" "$lineno" && continue
        report "$file" "$lineno" "$found" " (or add '// version-literal: <reason>')"
    done < <(grep -nE "<version>$VERSION_RE</version>|[0-9]+\.[0-9]+\.[0-9]+-rc[0-9]+" "$file" || true)
}

check_java() {
    for module in core integrations jbct aether; do
        [ -d "$SCAN_ROOT/$module" ] || continue
        while IFS= read -r javafile; do
            check_java_file "$javafile"
        done < <(find "$SCAN_ROOT/$module" -path '*/src/main/*' -name '*.java' -not -path '*/target/*' 2>/dev/null || true)
    done
}

for rel in README.md aether/README.md core/README.md jbct/README.md; do
    check_readme "$SCAN_ROOT/$rel"
done
check_example_poms
check_blueprints
check_java

if [ "$violations" -gt 0 ]; then
    echo "version-sweep: $violations violation(s) — expected canonical version $CANONICAL" >&2
    exit 1
fi

echo "version-sweep: clean — all checked version literals match canonical $CANONICAL"
exit 0
