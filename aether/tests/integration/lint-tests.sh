#!/bin/bash
#
# lint-tests.sh — Integration test linter
#
# Catches the 5 most common test anti-patterns identified by the 2026-05-21 audit.
# Wired into ./build.sh and run-tests.sh pre-flight in Phase 6 of the production-readiness plan
# (aether/docs/internal/production-readiness-plan-2026-05-21.md).
#
# Usage:
#   ./aether/tests/integration/lint-tests.sh [MODE] [--baseline <file>]
#
#   --strict       (default) exit non-zero on any finding not in the baseline
#   --report-only  exit 0 always; list findings to stdout
#   --capture      print findings in baseline-format to stdout; exit 0
#   --baseline F   compare against baseline file F; fail only on new findings
#                  (default: aether/tests/integration/lint-baseline.txt)
#
# Opt-out mechanism: tests may add an inline comment `# WARN_PASS_OK: <reason>`
# immediately after a `log_warn ... log_pass` pattern to acknowledge an intentional
# soft-gate. The reason must be specific.
#
# Rules:
#   R1: warn-then-pass demotion (log_warn ... log_pass in same control flow)
#   R2: 2>/dev/null || true inside suites/** (silent stderr trap)
#   R3: assert_ne <var> "" on raw HTTP response (tautology — see audit §2.1)
#   R4: [ status -ge 200 ] && [ status -lt 400 ] outside lib/load.sh (3xx-as-success)
#   R5: test_* function defined but never invoked via run_test (dead code)
#
# References:
#   audit: aether/docs/internal/audits/integration-test-audit-2026-05-21.md
#   memory: feedback_silent_stderr_is_a_trap, feedback_prefer_aether_cli

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR" && pwd)"
SUITES_DIR="$ROOT_DIR/suites"
LIB_DIR="$ROOT_DIR/lib"
REPO_ROOT="$(cd "$ROOT_DIR/../../.." && pwd)"
DEFAULT_BASELINE="$ROOT_DIR/lint-baseline.txt"

MODE="--strict"
BASELINE="$DEFAULT_BASELINE"
while [ $# -gt 0 ]; do
    case "$1" in
        --strict|--report-only|--capture) MODE="$1"; shift ;;
        --baseline) BASELINE="$2"; shift 2 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done

FINDINGS_FILE=$(mktemp)
trap "rm -f '$FINDINGS_FILE'" EXIT

red()    { printf "\033[31m%s\033[0m\n" "$*"; }
yellow() { printf "\033[33m%s\033[0m\n" "$*"; }
green()  { printf "\033[32m%s\033[0m\n" "$*"; }

emit_finding() {
    local rule="$1" file="$2" line="$3" detail="$4"
    # Normalize: strip $REPO_ROOT prefix to make findings portable across checkouts.
    local rel="${file#$REPO_ROOT/}"
    printf "[%s] %s:%s — %s\n" "$rule" "$rel" "$line" "$detail" >> "$FINDINGS_FILE"
}

# ============================================================
# R1 — warn-then-pass demotion
# ============================================================
lint_r1_warn_then_pass() {
    local file="$1"
    awk '
        /^[[:space:]]*test_[A-Za-z_][A-Za-z0-9_]*\(\)[[:space:]]*\{/ { in_test = 1; brace = 1; next }
        in_test && /\{/ { for (i=1; i<=NF; i++) if ($i ~ /\{/) brace++ }
        in_test && /\}/ { for (i=1; i<=NF; i++) if ($i ~ /\}/) brace--; if (brace == 0) { in_test = 0; next } }
        !in_test { next }
        /log_warn/ {
            warn_line = NR
            warn_seen = 1
            opt_out = 0
            next
        }
        warn_seen && /# WARN_PASS_OK:/ { opt_out = 1; next }
        warn_seen && (/^[[:space:]]*return/ || /log_fail/ || /^[[:space:]]*\}/) {
            warn_seen = 0; opt_out = 0; next
        }
        warn_seen && /log_pass/ {
            if (!opt_out && (NR - warn_line) <= 5) {
                print FILENAME ":" NR ":warn-then-pass-demotion (preceding log_warn at line " warn_line ")"
            }
            warn_seen = 0
        }
    ' "$file"
}

while IFS= read -r line; do
    [ -z "$line" ] && continue
    file=$(echo "$line" | cut -d: -f1)
    lineno=$(echo "$line" | cut -d: -f2)
    detail=$(echo "$line" | cut -d: -f3-)
    emit_finding R1 "$file" "$lineno" "$detail"
done < <(find "$SUITES_DIR" -name "*.sh" -exec bash -c "$(declare -f lint_r1_warn_then_pass); lint_r1_warn_then_pass \"\$1\"" _ {} \;)

# ============================================================
# R2 — silent stderr trap
# ============================================================
while IFS=: read -r file lineno match; do
    [ -z "$file" ] && continue
    emit_finding R2 "$file" "$lineno" "$(echo "$match" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
done < <(grep -rnE '2>/dev/null[[:space:]]*\|\|[[:space:]]*true' "$SUITES_DIR" 2>/dev/null)

# ============================================================
# R3 — assert_ne on raw HTTP response
# ============================================================
lint_r3_raw_response() {
    local file="$1"
    awk '
        /^[[:space:]]*test_[A-Za-z_][A-Za-z0-9_]*\(\)[[:space:]]*\{/ { in_test = 1; brace = 1; delete src; next }
        in_test && /\{/ { for (i=1; i<=NF; i++) if ($i ~ /\{/) brace++ }
        in_test && /\}/ { for (i=1; i<=NF; i++) if ($i ~ /\}/) brace--; if (brace == 0) { in_test = 0; delete src; next } }
        !in_test { next }
        /^[[:space:]]*local[[:space:]]+[a-zA-Z_][a-zA-Z0-9_]*=[[:space:]]*\$\(/ ||
        /^[[:space:]]*[a-zA-Z_][a-zA-Z0-9_]*=[[:space:]]*\$\(/ {
            varname = $0
            sub(/^[[:space:]]*(local[[:space:]]+)?/, "", varname)
            sub(/=.*/, "", varname)
            if ($0 ~ /(curl|api_get|api_post|api_put|api_delete|node_api_get|node_api_post|app_get|app_post)/) {
                src[varname] = NR
            }
        }
        /assert_ne[[:space:]]+"\$[a-zA-Z_][a-zA-Z0-9_]*"[[:space:]]+""/ {
            match($0, /assert_ne[[:space:]]+"\$[a-zA-Z_][a-zA-Z0-9_]*"/)
            if (RSTART > 0) {
                v = substr($0, RSTART + 10, RLENGTH - 10)
                gsub(/[" $]/, "", v)
                if (v in src && (NR - src[v]) <= 15) {
                    print FILENAME ":" NR ":tautological assert_ne on raw response (var $" v " from line " src[v] ")"
                }
            }
        }
    ' "$file"
}

while IFS= read -r line; do
    [ -z "$line" ] && continue
    file=$(echo "$line" | cut -d: -f1)
    lineno=$(echo "$line" | cut -d: -f2)
    detail=$(echo "$line" | cut -d: -f3-)
    emit_finding R3 "$file" "$lineno" "$detail"
done < <(find "$SUITES_DIR" -name "*.sh" -exec bash -c "$(declare -f lint_r3_raw_response); lint_r3_raw_response \"\$1\"" _ {} \;)

# ============================================================
# R4 — 3xx-as-success outside lib/load.sh
# ============================================================
while IFS=: read -r file lineno match; do
    [ -z "$file" ] && continue
    emit_finding R4 "$file" "$lineno" "$(echo "$match" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
done < <(grep -rnE '\[[[:space:]]*"?\$?[a-zA-Z_]+"?[[:space:]]+-ge[[:space:]]+200[[:space:]]*\][[:space:]]*&&[[:space:]]*\[[[:space:]]*"?\$?[a-zA-Z_]+"?[[:space:]]+-lt[[:space:]]+400[[:space:]]*\]' "$SUITES_DIR" 2>/dev/null)

# ============================================================
# R5 — test_* defined but never invoked
# ============================================================
lint_r5_dead_test() {
    local file="$1"
    local defined invoked
    defined=$(grep -nE '^[[:space:]]*test_[A-Za-z_][A-Za-z0-9_]*\(\)[[:space:]]*\{' "$file" | awk -F: '{
        sub(/\(\).*/, "", $2);
        sub(/^[[:space:]]*/, "", $2);
        print $1 ":" $2
    }')
    invoked=$(grep -oE 'run_test[[:space:]]+"[^"]*"[[:space:]]+test_[A-Za-z_][A-Za-z0-9_]*' "$file" | awk '{print $NF}' | sort -u)
    invoked="$invoked
$(grep -oE 'run_test[[:space:]]+test_[A-Za-z_][A-Za-z0-9_]*' "$file" | awk '{print $NF}' | sort -u)"
    invoked=$(echo "$invoked" | sort -u | grep -v '^$')

    while IFS=: read -r lineno name; do
        [ -z "$name" ] && continue
        if ! echo "$invoked" | grep -qx "$name"; then
            echo "$file:$lineno:$name defined but never invoked via run_test"
        fi
    done <<< "$defined"
}

while IFS= read -r line; do
    [ -z "$line" ] && continue
    file=$(echo "$line" | cut -d: -f1)
    lineno=$(echo "$line" | cut -d: -f2)
    detail=$(echo "$line" | cut -d: -f3-)
    emit_finding R5 "$file" "$lineno" "$detail"
done < <(find "$SUITES_DIR" -name "test-*.sh" -exec bash -c "$(declare -f lint_r5_dead_test); lint_r5_dead_test \"\$1\"" _ {} \;)

# ============================================================
# Report
# ============================================================

sort -o "$FINDINGS_FILE" "$FINDINGS_FILE"
TOTAL=$(wc -l < "$FINDINGS_FILE" | tr -d ' ')

case "$MODE" in
    --capture)
        cat "$FINDINGS_FILE"
        exit 0
        ;;
    --report-only)
        sed 's|^|  |' "$FINDINGS_FILE"
        echo ""
        if [ "$TOTAL" -eq 0 ]; then
            green "lint: 0 findings"
        else
            yellow "lint: $TOTAL findings (report-only mode, exit 0)"
        fi
        exit 0
        ;;
    --strict)
        if [ ! -f "$BASELINE" ]; then
            sed 's|^|  |' "$FINDINGS_FILE"
            echo ""
            if [ "$TOTAL" -eq 0 ]; then
                green "lint: 0 findings"
                exit 0
            fi
            red "lint: $TOTAL findings (strict mode, no baseline file at $BASELINE)"
            exit 1
        fi
        # Diff current findings against baseline
        NEW_FINDINGS=$(comm -23 <(sort "$FINDINGS_FILE") <(sort "$BASELINE"))
        STALE_BASELINE=$(comm -13 <(sort "$FINDINGS_FILE") <(sort "$BASELINE"))
        NEW_COUNT=$(echo -n "$NEW_FINDINGS" | grep -c '^' || true)
        STALE_COUNT=$(echo -n "$STALE_BASELINE" | grep -c '^' || true)
        if [ "$STALE_COUNT" -gt 0 ]; then
            yellow "lint: $STALE_COUNT baseline entries no longer present (please remove from baseline):"
            echo "$STALE_BASELINE" | sed 's|^|  |'
            echo ""
        fi
        if [ "$NEW_COUNT" -gt 0 ]; then
            red "lint: $NEW_COUNT NEW finding(s) not in baseline ($BASELINE):"
            echo "$NEW_FINDINGS" | sed 's|^|  |'
            echo ""
            red "Total: $TOTAL findings (baseline allows $(wc -l < "$BASELINE" | tr -d ' '))."
            red "To accept these as known issues, append to $BASELINE."
            exit 1
        fi
        green "lint: $TOTAL findings (all in baseline; 0 new)"
        exit 0
        ;;
esac
