#!/usr/bin/env bash
# Fold changelog.d/*.md fragments into the Unreleased section of CHANGELOG.md.
#
#   scripts/changelog-assemble.sh            writes CHANGELOG.md, git-removes the fragments
#   scripts/changelog-assemble.sh --dry-run  prints the assembled Unreleased section, changes nothing
#
# Fragments go in at the top of the section, newest date first (ties by descending number), so the
# section keeps its existing newest-first order. See changelog.d/README.md for the fragment format.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
changelog="$root/CHANGELOG.md"
dir="$root/changelog.d"
dry_run=false
[[ "${1:-}" == "--dry-run" ]] && dry_run=true

shopt -s nullglob
fragments=("$dir"/[0-9]*-*.md)
if (( ${#fragments[@]} == 0 )); then
    echo "changelog-assemble: no fragments in changelog.d/" >&2
    exit 0
fi

heading_line="$(grep -n -m1 '^## \[.*\] - Unreleased$' "$changelog" | cut -d: -f1 || true)"
if [[ -z "$heading_line" ]]; then
    echo "changelog-assemble: CHANGELOG.md has no '## [<version>] - Unreleased' heading" >&2
    exit 1
fi

# Sort key per fragment: date from the sub-heading (YYYY-MM-DD, 0000-00-00 when absent), then the
# leading number of the file name; both descending.
keyed=()
for f in "${fragments[@]}"; do
    first="$(grep -m1 '^### ' "$f" || true)"
    if [[ -z "$first" ]]; then
        echo "changelog-assemble: $f has no '### <Section>' sub-heading" >&2
        exit 1
    fi
    date="$(grep -o '[0-9]\{4\}-[0-9]\{2\}-[0-9]\{2\}' <<<"$first" | head -1 || true)"
    number="$(basename "$f" | grep -o '^[0-9]*')"
    keyed+=("${date:-0000-00-00} $number $f")
done

assembled="$(mktemp)"
trap 'rm -f "$assembled"' EXIT
printf '%s\n' "${keyed[@]}" | sort -k1,1r -k2,2nr | while read -r _ _ f; do
    # Drop leading and trailing blank lines, keep inner ones, then one separating blank line.
    # awk rather than sed: the GNU and BSD seds disagree on the multi-line idioms.
    awk 'NF { for (i = 0; i < blank; i++) print ""; blank = 0; print; next } { blank++ }' "$f"
    echo
done > "$assembled"

output="$(mktemp)"
trap 'rm -f "$assembled" "$output"' EXIT
{
    # The heading and the blank line under it, then the fragments, then the rest of the file
    # without the blank line that followed the heading.
    sed -n "1,${heading_line}p" "$changelog"
    echo
    cat "$assembled"
    awk -v s="$((heading_line + 1))" 'NR >= s && !(NR == s && $0 == "")' "$changelog"
} > "$output"

if $dry_run; then
    next_heading="$(awk -v start="$((heading_line + 1))" 'NR > start && /^## \[/ {print NR; exit}' "$output")"
    sed -n "${heading_line},$(( ${next_heading:-$(wc -l < "$output")} - 1 ))p" "$output"
    exit 0
fi

cp "$output" "$changelog"
git -C "$root" rm -q -- "${fragments[@]}"
echo "changelog-assemble: folded ${#fragments[@]} fragment(s) into CHANGELOG.md"
