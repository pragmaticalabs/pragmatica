#!/bin/bash
#
# lint-tests.sh — Integration test linter
#
# Catches the 5 most common test anti-patterns identified by the 2026-05-21 audit.
# Wired into ./build.sh and run-tests.sh pre-flight in Phase 6 of the production-readiness plan
# (aether/docs/internal/production-readiness-plan-2026-05-21.md). For now: invoke manually.
#
# Usage:
#   ./aether/tests/integration/lint-tests.sh [--strict|--report-only]
#
#   --strict       exit non-zero on any finding (default)
#   --report-only  exit 0 always; list findings to stdout
#
# Opt-out mechanism: tests may add an inline comment `# WARN_PASS_OK: <reason>`
# immediately after a `log_warn ... log_pass` pattern to acknowledge an intentional
# soft-gate. The reason must be specific (e.g., "passive observation; load.sh
# is the hard assertion in the same function").
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

set -uo pipefail  # NOT -e — we want all rules to run even if one finds issues

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR" && pwd)"
SUITES_DIR="$ROOT_DIR/suites"
LIB_DIR="$ROOT_DIR/lib"

MODE="${1:---strict}"
FINDINGS=0

red()    { printf "\033[31m%s\033[0m\n" "$*"; }
yellow() { printf "\033[33m%s\033[0m\n" "$*"; }
green()  { printf "\033[32m%s\033[0m\n" "$*"; }

report_finding() {
    local rule="$1" file="$2" line="$3" detail="$4"
    red "  [$rule] $file:$line — $detail"
    FINDINGS=$((FINDINGS + 1))
}

# ============================================================
# R1 — warn-then-pass demotion
# ============================================================
# Detect: within a test_* function body, a log_warn line followed within 5 non-blank lines
# by a log_pass line without an intervening `return`, `log_fail`, or a `# WARN_PASS_OK:` comment.

lint_r1_warn_then_pass() {
    local file="$1"
    awk '
        # Track when we enter/exit a test_* function
        /^[[:space:]]*test_[A-Za-z_][A-Za-z0-9_]*\(\)[[:space:]]*\{/ { in_test = 1; brace = 1; next }
        in_test && /\{/ { for (i=1; i<=NF; i++) if ($i ~ /\{/) brace++ }
        in_test && /\}/ { for (i=1; i<=NF; i++) if ($i ~ /\}/) brace--; if (brace == 0) { in_test = 0; next } }
        !in_test { next }
        # Saw log_warn — start a watch window
        /log_warn/ {
            warn_line = NR
            warn_seen = 1
            opt_out = 0
            next
        }
        # Opt-out comment within the window
        warn_seen && /# WARN_PASS_OK:/ { opt_out = 1; next }
        # Reset window on return/log_fail/else
        warn_seen && (/^[[:space:]]*return/ || /log_fail/ || /^[[:space:]]*\}/) {
            warn_seen = 0; opt_out = 0; next
        }
        # Find log_pass within 5 non-blank lines
        warn_seen && /log_pass/ {
            if (!opt_out && (NR - warn_line) <= 5) {
                print FILENAME ":" NR ":warn-then-pass-demotion (preceding log_warn at line " warn_line ")"
            }
            warn_seen = 0
        }
    ' "$file"
}

echo "=== R1 — warn-then-pass demotion ==="
while IFS= read -r line; do
    [ -z "$line" ] && continue
    file=$(echo "$line" | cut -d: -f1)
    lineno=$(echo "$line" | cut -d: -f2)
    detail=$(echo "$line" | cut -d: -f3-)
    report_finding R1 "$file" "$lineno" "$detail"
done < <(find "$SUITES_DIR" -name "*.sh" -exec bash -c "$(declare -f lint_r1_warn_then_pass); lint_r1_warn_then_pass \"\$1\"" _ {} \;)

# ============================================================
# R2 — silent stderr trap (2>/dev/null || true)
# ============================================================

echo "=== R2 — silent stderr trap ==="
while IFS=: read -r file lineno match; do
    [ -z "$file" ] && continue
    report_finding R2 "$file" "$lineno" "$(echo "$match" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
done < <(grep -rnE '2>/dev/null[[:space:]]*\|\|[[:space:]]*true' "$SUITES_DIR" 2>/dev/null)

# ============================================================
# R3 — assert_ne ... "" on raw HTTP response
# ============================================================
# Heuristic: a line `assert_ne "$X" ""` where $X was assigned from curl/api_get/api_post/api_put/api_delete
# in the previous 10 lines of the same function body.

echo "=== R3 — assert_ne on raw HTTP response ==="
lint_r3_raw_response() {
    local file="$1"
    awk '
        /^[[:space:]]*test_[A-Za-z_][A-Za-z0-9_]*\(\)[[:space:]]*\{/ { in_test = 1; brace = 1; delete src; next }
        in_test && /\{/ { for (i=1; i<=NF; i++) if ($i ~ /\{/) brace++ }
        in_test && /\}/ { for (i=1; i<=NF; i++) if ($i ~ /\}/) brace--; if (brace == 0) { in_test = 0; delete src; next } }
        !in_test { next }
        # Track variable assignments from curl/api_*
        /^[[:space:]]*local[[:space:]]+[a-zA-Z_][a-zA-Z0-9_]*=[[:space:]]*\$\(/ ||
        /^[[:space:]]*[a-zA-Z_][a-zA-Z0-9_]*=[[:space:]]*\$\(/ {
            varname = $0
            sub(/^[[:space:]]*(local[[:space:]]+)?/, "", varname)
            sub(/=.*/, "", varname)
            if ($0 ~ /(curl|api_get|api_post|api_put|api_delete|node_api_get|node_api_post|app_get|app_post)/) {
                src[varname] = NR
            }
        }
        # Detect tautological assert_ne
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
    report_finding R3 "$file" "$lineno" "$detail"
done < <(find "$SUITES_DIR" -name "*.sh" -exec bash -c "$(declare -f lint_r3_raw_response); lint_r3_raw_response \"\$1\"" _ {} \;)

# ============================================================
# R4 — 3xx-as-success outside lib/load.sh
# ============================================================

echo "=== R4 — 3xx-as-success ==="
while IFS=: read -r file lineno match; do
    [ -z "$file" ] && continue
    # Allow lib/load.sh which is documented as a load-helper-only exception
    case "$file" in
        */lib/load.sh) continue ;;
    esac
    report_finding R4 "$file" "$lineno" "$(echo "$match" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
done < <(grep -rnE '\[[[:space:]]*"?\$?[a-zA-Z_]+"?[[:space:]]+-ge[[:space:]]+200[[:space:]]*\][[:space:]]*&&[[:space:]]*\[[[:space:]]*"?\$?[a-zA-Z_]+"?[[:space:]]+-lt[[:space:]]+400[[:space:]]*\]' "$SUITES_DIR" 2>/dev/null)

# ============================================================
# R5 — test_* defined but never invoked via run_test
# ============================================================

echo "=== R5 — test_* defined but never invoked ==="
lint_r5_dead_test() {
    local file="$1"
    local defined invoked
    defined=$(grep -nE '^[[:space:]]*test_[A-Za-z_][A-Za-z0-9_]*\(\)[[:space:]]*\{' "$file" | awk -F: '{
        sub(/\(\).*/, "", $2);
        sub(/^[[:space:]]*/, "", $2);
        print $1 ":" $2
    }')
    # run_test is invoked as: run_test "description" test_function_name
    # Extract the second argument (which may follow a quoted string)
    invoked=$(grep -oE 'run_test[[:space:]]+"[^"]*"[[:space:]]+test_[A-Za-z_][A-Za-z0-9_]*' "$file" | \
              awk '{print $NF}' | sort -u)
    # Also catch run_test test_X (no description) and run_test SKIPPED/PENDING test_X forms
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
    report_finding R5 "$file" "$lineno" "$detail"
done < <(find "$SUITES_DIR" -name "test-*.sh" -exec bash -c "$(declare -f lint_r5_dead_test); lint_r5_dead_test \"\$1\"" _ {} \;)

# ============================================================
# Report
# ============================================================

echo ""
if [ "$FINDINGS" -eq 0 ]; then
    green "lint: 0 findings"
    exit 0
fi

case "$MODE" in
    --report-only)
        yellow "lint: $FINDINGS findings (report-only mode, exit 0)"
        exit 0
        ;;
    *)
        red "lint: $FINDINGS findings (strict mode)"
        exit 1
        ;;
esac
