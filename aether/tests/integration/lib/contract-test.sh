#!/bin/bash
#
# contract-test.sh — Management API schema-stability contract test (harness-resilience spec A3)
#
# A lib-level unit test (no live cluster). Enforces that the response fields the harness lib
# functions parse remain stable, and that a product-side field rename cannot silently break
# grep-based parsing the way the `id` -> `nodeId` rename did (a real cloud failure).
#
# It reads the frozen-field contract from lib/schema-contract.toml and checks two invariants:
#
#   CHECK 1 (drift guard) — every frozen field is present in the documented response shape in
#            aether/docs/reference/management-api.md. If the product renames a frozen field, the
#            doc loses it and this check fails — surfacing the break at push time, not on a cloud
#            run. (Docs are kept in lockstep with code via the REST->CLI->Docs triad invariant.)
#
#   CHECK 2 (stale-parser guard) — no harness lib (lib/common.sh, lib/cluster.sh, lib/topology.sh)
#            greps a frozen field's pre-freeze former name as a quoted JSON key. A match means a
#            renamed field's old name leaked back into a parser. `former_names` in the TOML lists
#            the pre-freeze names (e.g. nodeId was once id).
#
# Usage:
#   ./aether/tests/integration/lib/contract-test.sh            # strict, exit non-zero on drift
#   ./aether/tests/integration/lib/contract-test.sh --report-only
#
# Wired into lint-tests.sh's invocation point in run-tests.sh (Step 0, alongside lint-tests.sh).
#
# References:
#   spec: aether/docs/specs/harness-resilience-spec.md §A3
#   doc:  aether/docs/reference/management-api.md ("Schema stability contract")

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LIB_DIR="$SCRIPT_DIR"
ROOT_DIR="$(cd "$LIB_DIR/.." && pwd)"
REPO_ROOT="$(cd "$ROOT_DIR/../../.." && pwd)"
CONTRACT_TOML="$LIB_DIR/schema-contract.toml"
API_DOC="$REPO_ROOT/aether/docs/reference/management-api.md"
SCAN_LIBS=("$LIB_DIR/common.sh" "$LIB_DIR/cluster.sh" "$LIB_DIR/topology.sh")

MODE="--strict"
case "${1:-}" in
    --report-only) MODE="--report-only" ;;
    --strict|"") MODE="--strict" ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
esac

red()   { printf "\033[31m%s\033[0m\n" "$*"; }
green() { printf "\033[32m%s\033[0m\n" "$*"; }

FAILURES=0
fail() { red "  FAIL: $*"; FAILURES=$((FAILURES + 1)); }

# --- Preconditions -------------------------------------------------------
[ -f "$CONTRACT_TOML" ] || { red "contract file not found: $CONTRACT_TOML"; exit 2; }
[ -f "$API_DOC" ]       || { red "API doc not found: $API_DOC"; exit 2; }

# --- Parse frozen_fields lines from the TOML (no external TOML parser) ----
# Each `frozen_fields = ["a", "b", "c"]` line yields its quoted tokens, one per output line.
frozen_fields() {
    grep -E '^[[:space:]]*frozen_fields[[:space:]]*=' "$CONTRACT_TOML" \
        | grep -oE '"[^"]+"' \
        | tr -d '"' \
        | sort -u
}

# --- Parse former_names mapping (new_name = "old_name") -------------------
# Emits the old (former) name tokens, one per line.
former_names() {
    awk '
        /^\[former_names\]/ { in_block = 1; next }
        /^\[/ { in_block = 0 }
        in_block && /=/ {
            if (match($0, /"[^"]+"/)) {
                print substr($0, RSTART + 1, RLENGTH - 2)
            }
        }
    ' "$CONTRACT_TOML" | sort -u
}

# ============================================================
# CHECK 1 — every frozen field appears in the documented shape
# ============================================================
green "CHECK 1 — frozen fields present in management-api.md schema contract"
while IFS= read -r field; do
    [ -z "$field" ] && continue
    # The frozen field must appear as a quoted JSON key in the doc's example responses
    # (`"field":`), guaranteeing the documented shape still carries it.
    if grep -qE "\"$field\"[[:space:]]*:" "$API_DOC"; then
        :
    else
        fail "frozen field '$field' is absent from $API_DOC — was it renamed? (drift)"
    fi
done < <(frozen_fields)

# ============================================================
# CHECK 2 — no harness lib greps a frozen field's former name
# ============================================================
green "CHECK 2 — harness libs free of pre-freeze field names"
while IFS= read -r old; do
    [ -z "$old" ] && continue
    for lib in "${SCAN_LIBS[@]}"; do
        [ -f "$lib" ] || continue
        # Match the former name only as a top-level QUOTED JSON key (`"id":`), the form used in
        # grep/sed status parsers. Two exclusions avoid false positives:
        #   - a nested object key (`"nodeId":{"id":...}`) is the EMITTER id, not a stale top-level
        #     identifier — exclude any `"id"` immediately preceded by `:{`.
        #   - lines that are pure comments (leading `#`) document parsing, they don't parse.
        hits=$(grep -nE "\"$old\"[[:space:]]*:" "$lib" 2>/dev/null \
                | grep -vE ":[[:space:]]*#" \
                | grep -vE "\{\"$old\"[[:space:]]*:")
        if [ -n "$hits" ]; then
            rel="${lib#$REPO_ROOT/}"
            while IFS= read -r h; do
                [ -z "$h" ] && continue
                fail "$rel:${h%%:*} greps pre-freeze field \"$old\" (frozen name applies — stale parser)"
            done <<< "$hits"
        fi
    done
done < <(former_names)

# ============================================================
# Report
# ============================================================
echo ""
if [ "$FAILURES" -eq 0 ]; then
    green "contract: 0 violations (schema stable)"
    exit 0
fi

if [ "$MODE" = "--report-only" ]; then
    red "contract: $FAILURES violation(s) (report-only mode, exit 0)"
    exit 0
fi

red "contract: $FAILURES violation(s) — schema drift detected"
red "If a rename is intentional, bump the major version, update management-api.md, and adjust"
red "lib/schema-contract.toml + the affected harness lib parsers in the same change."
exit 1
