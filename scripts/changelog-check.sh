#!/usr/bin/env bash
# Pull-request check for changelog fragments. See changelog.d/README.md.
#
#   scripts/changelog-check.sh <base-ref>
#
# PR_LABELS (comma-separated) may carry `no-changelog` (no fragment required) or `release-prep`
# (CHANGELOG.md may be edited). Fails when CHANGELOG.md is edited without `release-prep`, or when
# non-documentation files change without a well-formed fragment and without `no-changelog`.
set -euo pipefail

base="${1:?usage: changelog-check.sh <base-ref>}"
labels=",${PR_LABELS:-},"
root="$(git rev-parse --show-toplevel)"
cd "$root"

has_label() { [[ "$labels" == *",$1,"* ]]; }

# No mapfile: macOS ships bash 3.2 and this runs locally too.
changed=()
while IFS= read -r line; do changed+=("$line"); done < <(git diff --name-only --diff-filter=ACDMR "$base...HEAD")
if (( ${#changed[@]} == 0 )); then
    echo "changelog-check: no changes against $base"
    exit 0
fi

status=0

if printf '%s\n' "${changed[@]}" | grep -qx 'CHANGELOG.md' && ! has_label release-prep; then
    echo "changelog-check: CHANGELOG.md is assembled at release prep; put the entry in changelog.d/<number>-<slug>.md instead (or label the PR release-prep)" >&2
    status=1
fi

# Paths that never need a fragment.
is_exempt() {
    case "$1" in
        changelog.d/*|*.md|docs/*|.github/*|LICENSE*|.gitignore|.gitattributes) return 0 ;;
        *) return 1 ;;
    esac
}

needs_fragment=false
for path in "${changed[@]}"; do
    is_exempt "$path" || { needs_fragment=true; break; }
done

fragments=()
while IFS= read -r line; do [[ -n "$line" ]] && fragments+=("$line"); done < <(git diff --name-only --diff-filter=AM "$base...HEAD" -- 'changelog.d/*.md' | grep -v '/README.md$' || true)

section_re='^### (Added|Changed|Deprecated|Removed|Fixed|Security|Performance)( \(.*\))?$'
well_formed=0
# ${arr[@]+"${arr[@]}"}: bash 3.2 treats an empty array as unbound under set -u.
for f in ${fragments[@]+"${fragments[@]}"}; do
    name="$(basename "$f")"
    if ! [[ "$name" =~ ^[0-9]+-[a-z0-9-]+\.md$ ]]; then
        echo "changelog-check: $f: file name must be <number>-<slug>.md (lowercase, digits, dashes)" >&2
        status=1
        continue
    fi
    first="$(grep -m1 -v '^[[:space:]]*$' "$f" || true)"
    if ! [[ "$first" =~ $section_re ]]; then
        echo "changelog-check: $f: first line must be '### <Section> (...)' with Section one of Added, Changed, Deprecated, Removed, Fixed, Security, Performance" >&2
        status=1
        continue
    fi
    if ! grep -q '^- ' "$f"; then
        echo "changelog-check: $f: needs at least one '- ' bullet under the sub-heading" >&2
        status=1
        continue
    fi
    well_formed=$((well_formed + 1))
done

if $needs_fragment && (( well_formed == 0 )) && ! has_label no-changelog; then
    echo "changelog-check: sources changed but no well-formed changelog.d/<number>-<slug>.md fragment was added (label the PR no-changelog if it truly needs no entry)" >&2
    status=1
fi

if (( status == 0 )); then
    echo "changelog-check: ok (${well_formed} fragment(s), needs_fragment=$needs_fragment)"
fi
exit $status
