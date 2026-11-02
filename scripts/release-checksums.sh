#!/usr/bin/env bash
# Release checksums (#2235): one SHA256SUMS line per published asset, bare file names, standard `sha256sum -c` format.
#
#   scripts/release-checksums.sh generate <dir>
#   scripts/release-checksums.sh verify <dir> [<listing>]
#
# generate writes <dir>/SHA256SUMS over every regular file in <dir> except SHA256SUMS itself. It runs AFTER the last asset exists (the
# release workflow's final job), because each platform archive is built by a different job.
#
# verify fails when (a) SHA256SUMS is missing or empty, (b) an asset is not listed in it, (c) it lists a name that is not an asset or
# carries a path, or (d) `sha256sum -c` fails. The expected asset set is <listing> (one published asset name per line, e.g. from
# `gh release view --json assets`) when given, else the files in <dir>. SHA256SUMS cannot list itself and is never expected in it.
set -euo pipefail

SUMS=SHA256SUMS

digest() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}

assets_in() {
  (cd "$1" && find . -maxdepth 1 -type f ! -name "$SUMS" | sed 's|^\./||' | LC_ALL=C sort)
}

generate() {
  local dir="$1"
  local assets
  assets="$(assets_in "$dir")"
  [[ -n "$assets" ]] || { echo "release-checksums: no assets in $dir" >&2; exit 1; }
  (cd "$dir" && printf '%s\n' "$assets" | while IFS= read -r name; do digest "$name"; done > "$SUMS")
  echo "release-checksums: $(wc -l < "$dir/$SUMS" | tr -d ' ') entries in $dir/$SUMS"
}

verify() {
  local dir="$1" listing="${2:-}"
  [[ -s "$dir/$SUMS" ]] || { echo "release-checksums: $dir/$SUMS missing or empty" >&2; exit 1; }
  local expected listed
  if [[ -n "$listing" ]]; then
    expected="$(grep -v -x -F "$SUMS" "$listing" | LC_ALL=C sort || true)"
  else
    expected="$(assets_in "$dir")"
  fi
  [[ -n "$expected" ]] || { echo "release-checksums: expected asset set is empty" >&2; exit 1; }
  listed="$(sed -n 's/^[0-9a-f]\{64\}  \*\{0,1\}\(.*\)$/\1/p' "$dir/$SUMS" | LC_ALL=C sort)"
  [[ "$(grep -c . "$dir/$SUMS")" == "$(printf '%s\n' "$listed" | grep -c .)" ]] \
    || { echo "release-checksums: $SUMS has lines that are not '<sha256>  <name>'" >&2; exit 1; }
  local missing extra
  missing="$(LC_ALL=C comm -23 <(printf '%s\n' "$expected") <(printf '%s\n' "$listed"))"
  extra="$(LC_ALL=C comm -13 <(printf '%s\n' "$expected") <(printf '%s\n' "$listed"))"
  [[ -z "$missing" ]] || { echo "release-checksums: assets missing from $SUMS:" >&2; echo "$missing" >&2; exit 1; }
  [[ -z "$extra" ]] || { echo "release-checksums: $SUMS names that are not assets (or carry a path):" >&2; echo "$extra" >&2; exit 1; }
  (cd "$dir" && digest -c "$SUMS")
  echo "release-checksums: $(printf '%s\n' "$expected" | grep -c .) assets verified"
}

case "${1:-}" in
  generate) generate "${2:?usage: release-checksums.sh generate <dir>}" ;;
  verify) verify "${2:?usage: release-checksums.sh verify <dir> [<listing>]}" "${3:-}" ;;
  *) echo "usage: release-checksums.sh generate <dir> | verify <dir> [<listing>]" >&2; exit 2 ;;
esac
