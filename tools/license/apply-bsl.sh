#!/usr/bin/env bash
# apply-bsl.sh — Apply BSL-1.1 SPDX header to .java files under BSL-licensed modules.
#
# Scope: aether/**, jbct/slice-processor/**, jbct/slice-processor-tests/**
# Excludes: target/, generated-sources/, .claude/, src/main/java-templates/
#
# Behavior per file:
#   - If BSL header already present → skip.
#   - If Apache-2.0 header present (our legacy block) → strip it + prepend BSL.
#   - If no header → prepend BSL.
#
# Usage:
#   tools/license/apply-bsl.sh [--dry-run]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HEADER_FILE="${REPO_ROOT}/docs/legal/bsl-header.txt"
DRY_RUN=false

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY_RUN=true; shift ;;
        -h|--help)
            echo "Usage: $0 [--dry-run]"
            exit 0
            ;;
        *) echo "Unknown argument: $1"; exit 2 ;;
    esac
done

if [ ! -f "$HEADER_FILE" ]; then
    echo "Header file not found: $HEADER_FILE" >&2
    exit 1
fi

HEADER_CONTENT="$(cat "$HEADER_FILE")"

# Target scope: where BSL applies
SCOPE_DIRS=(
    "${REPO_ROOT}/aether"
    "${REPO_ROOT}/jbct/slice-processor"
    "${REPO_ROOT}/jbct/slice-processor-tests"
)

# Collect candidate files (robust to spaces; no subshell var loss)
FILES=()
while IFS= read -r -d '' f; do
    FILES+=("$f")
done < <(
    find "${SCOPE_DIRS[@]}" -type f -name "*.java" \
        -not -path "*/target/*" \
        -not -path "*/generated-sources/*" \
        -not -path "*/.claude/*" \
        -print0
)

echo "Scanning ${#FILES[@]} Java files under BSL scope..."

ADDED=0
REPLACED=0
SKIPPED=0

for f in "${FILES[@]}"; do
    first_line="$(head -n 1 "$f")"
    if [[ "$first_line" == "// SPDX-License-Identifier: BUSL-1.1" ]]; then
        SKIPPED=$((SKIPPED + 1))
        continue
    fi

    # Detect Apache 2.0 block we want to replace
    if head -n 15 "$f" | grep -q "Licensed under the Apache License, Version 2.0"; then
        # Strip the leading /* ... */ block and blank-lines after it
        if [ "$DRY_RUN" = true ]; then
            REPLACED=$((REPLACED + 1))
            continue
        fi
        awk '
            BEGIN { in_block = 0; stripped = 0 }
            NR == 1 && /^\/\*/ { in_block = 1; next }
            in_block && /\*\// { in_block = 0; stripped = 1; next }
            in_block { next }
            !stripped || /./ { print; stripped = 0 }
            { print }
        ' "$f" > /dev/null 2>&1 || true

        # Simpler approach: use sed to delete first C-style block comment
        tmp="$(mktemp)"
        awk '
            BEGIN { in_block = 0; eating_blanks = 0 }
            NR == 1 && /^\/\*/ { in_block = 1; next }
            in_block {
                if (/\*\//) { in_block = 0; eating_blanks = 1 }
                next
            }
            eating_blanks {
                if (/^[[:space:]]*$/) { next }
                eating_blanks = 0
            }
            { print }
        ' "$f" > "$tmp"

        {
            echo "$HEADER_CONTENT"
            echo ""
            cat "$tmp"
        } > "$f"
        rm -f "$tmp"
        REPLACED=$((REPLACED + 1))
        continue
    fi

    # No recognizable header → prepend BSL
    if [ "$DRY_RUN" = true ]; then
        ADDED=$((ADDED + 1))
        continue
    fi
    tmp="$(mktemp)"
    {
        echo "$HEADER_CONTENT"
        echo ""
        cat "$f"
    } > "$tmp"
    mv "$tmp" "$f"
    ADDED=$((ADDED + 1))
done

echo "Summary:"
echo "  Added header (no prior):      ${ADDED}"
echo "  Replaced Apache-2.0 header:   ${REPLACED}"
echo "  Skipped (already BSL):        ${SKIPPED}"
echo "  Total:                        ${#FILES[@]}"

if [ "$DRY_RUN" = true ]; then
    echo "(dry run — no files modified)"
fi
