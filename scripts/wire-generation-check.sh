#!/usr/bin/env bash
# Pull-request check: a wire break bumps JoinGenerationGuard.WIRE_GENERATION by exactly one (#3303).
#
#   scripts/wire-generation-check.sh <base-ref>
#
# Compares the wire-assignment baseline committed at HEAD with the one at the merge base of <base-ref> and HEAD.
# A TAG, SHAPE or ENUM line of the base that is changed or removed at HEAD is a wire break: an old peer decodes the new
# bytes wrongly (a renumbered TAG routes it to the wrong codec), so HEAD's GENERATION line must equal the base generation + 1 and the join guard refuses the old
# peer. Any other change (added TAG, SHAPE or ENUM lines only, or no change) must leave GENERATION unchanged.
# A semantic wire break changes how replicas JUDGE an existing wire value while no TAG, SHAPE or ENUM line moves. It is
# declared in the baseline with a line  SEMANTIC <value> <one-line old->new judgement>  (illustrative:
# "SEMANTIC a.A.epoch stale-if-below -> stale-if-below-or-equal"). A SEMANTIC line ADDED at HEAD
# (absent at the merge base) is a wire break exactly like a changed TAG, SHAPE or ENUM line: HEAD's GENERATION must be
# base + 1. SEMANTIC lines already at the merge base are history and never re-trigger. History is append-only: a base
# SEMANTIC line that is removed or edited at HEAD is refused (exit 1, naming the line), and so is any SEMANTIC line that is
# not of the form  SEMANTIC <value> <text containing "->">.
# The base generation is the base baseline's GENERATION line; a base baseline without one (before the line
# existed) counts as WIRE_GENERATION read from JoinGenerationGuard.java at the merge base.
# A runtime test cannot do this: it never sees the base commit's constant, and a relative assertion stays green
# when the bump is missing (know ea094e261d). The baseline test pins HEAD's GENERATION line to the constant.
#
# Reads committed content only (git show), never the working tree, so the verdict is a function of the two commits.
# Exit codes, as in changelog-check.sh: 1 means the check LOOKED and refused, 2 means it COULD NOT LOOK and reports
# no verdict.
set -euo pipefail

base="${1:?usage: wire-generation-check.sh <base-ref>}"
root="$(git rev-parse --show-toplevel)"
cd "$root"

baseline="aether/node/src/test/resources/wire-assignment-baseline.txt"
guard="integrations/consensus/src/main/java/org/pragmatica/consensus/net/JoinGenerationGuard.java"

cannot_look() {
    echo "wire-generation-check: $1, so nothing was compared and this check EXAMINED NOTHING; refusing to report success" >&2
    exit 2
}

git rev-parse --verify --quiet "$base^{commit}" >/dev/null || cannot_look "base ref '$base' does not resolve to a commit"
merge_base="$(git merge-base "$base" HEAD)" || cannot_look "no merge base between '$base' and HEAD"

base_file="$(git show "$merge_base:$baseline")" || cannot_look "$baseline is absent at the merge base $merge_base"
head_file="$(git show "HEAD:$baseline")" || cannot_look "$baseline is absent at HEAD"

generation_line() {
    grep -E '^GENERATION ' <<<"$1" | awk '{print $2}' || true
}

base_generation="$(generation_line "$base_file")"
if [[ -z "$base_generation" ]]; then
    guard_file="$(git show "$merge_base:$guard")" || cannot_look "$guard is absent at the merge base $merge_base"
    base_generation="$(sed -nE 's/^[[:space:]]*int WIRE_GENERATION = ([0-9]+);.*/\1/p' <<<"$guard_file")"
    [[ -n "$base_generation" ]] || cannot_look "no GENERATION line in the base baseline and no WIRE_GENERATION in $guard at $merge_base"
    base_source="WIRE_GENERATION in $guard at the merge base"
else
    base_source="the GENERATION line at the merge base"
fi
[[ "$base_generation" =~ ^[0-9]+$ ]] || cannot_look "base generation '$base_generation' is not a number"

head_generation="$(generation_line "$head_file")"
if [[ ! "$head_generation" =~ ^[0-9]+$ ]]; then
    echo "wire-generation-check: HEAD's $baseline has no numeric GENERATION line; re-record it with WireAssignmentBaselineWriter" >&2
    exit 1
fi

wire_lines() {
    grep -E '^(TAG|SHAPE|ENUM) ' <<<"$1" | tr -s ' ' | sort -u || true
}

# SEMANTIC lines are compared verbatim (an edit, even of whitespace, is an edit of history).
semantic_lines() {
    grep -E '^SEMANTIC($|[[:space:]])' <<<"$1" | sort -u || true
}

broken="$(comm -23 <(wire_lines "$base_file") <(wire_lines "$head_file"))"
base_semantic="$(semantic_lines "$base_file")"
head_semantic="$(semantic_lines "$head_file")"

malformed="$(printf '%s\n' "$head_semantic" | sed '/^$/d' | grep -Ev '^SEMANTIC [^[:space:]]+ [^[:space:]].*->.*[^[:space:]]$' || true)"
if [[ -n "$malformed" ]]; then
    echo "wire-generation-check: REFUSED: malformed SEMANTIC line(s) at HEAD; the form is: SEMANTIC <value> <one-line old->new judgement>:" >&2
    sed 's/^/  - /' <<<"$malformed" >&2
    exit 1
fi

semantic_gone="$(comm -23 <(printf '%s\n' "$base_semantic" | sed '/^$/d') <(printf '%s\n' "$head_semantic" | sed '/^$/d'))"
if [[ -n "$semantic_gone" ]]; then
    echo "wire-generation-check: REFUSED: SEMANTIC line(s) of the base removed or edited at HEAD; the declaration history is append-only:" >&2
    sed 's/^/  - /' <<<"$semantic_gone" >&2
    exit 1
fi

semantic_added="$(comm -13 <(printf '%s\n' "$base_semantic" | sed '/^$/d') <(printf '%s\n' "$head_semantic" | sed '/^$/d'))"

echo "wire-generation-check: merge base $merge_base, base generation $base_generation ($base_source), HEAD generation $head_generation"
if [[ -n "$broken" || -n "$semantic_added" ]]; then
    if [[ -n "$broken" ]]; then
        echo "wire-generation-check: $(wc -l <<<"$broken" | tr -d ' ') TAG/SHAPE/ENUM line(s) of the base changed or removed at HEAD (a wire break):"
        sed 's/^/  - /' <<<"$broken"
    fi
    if [[ -n "$semantic_added" ]]; then
        echo "wire-generation-check: $(wc -l <<<"$semantic_added" | tr -d ' ') SEMANTIC line(s) added at HEAD (a declared semantic wire break):"
        sed 's/^/  - /' <<<"$semantic_added"
    fi
    required=$((base_generation + 1))
    reason="a wire break requires GENERATION = base + 1 = $required (bump JoinGenerationGuard.WIRE_GENERATION, then re-record the baseline)"
else
    echo "wire-generation-check: no TAG/SHAPE/ENUM line of the base changed or removed and no SEMANTIC line added (added-only or no wire change)"
    required=$base_generation
    reason="a change without a wire break requires GENERATION unchanged = $required"
fi

if [[ "$head_generation" -ne "$required" ]]; then
    echo "wire-generation-check: REFUSED: HEAD generation is $head_generation; $reason" >&2
    exit 1
fi
echo "wire-generation-check: OK: HEAD generation $head_generation; $reason"
