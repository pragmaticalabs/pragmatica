#!/bin/bash
# test-generation-epoch.sh — offline probes pinning lib/generation.sh's epoch handling (#1529).
# The await-quiesced route accepts only incarnation:term:counter; a harness that still sent
# term:counter got a 400 on every barrier, and callers wrapped in `|| true` / `|| log_warn` turned
# every barrier into a silent no-op. These probes pin both halves:
#   E1-E4 the epoch string: read from the top-level `epoch` object only, `current+N` bumps the
#         counter only, a literal I:T:C passes through;
#   E5-E7 fail loudly: a 400, or a spec the harness cannot turn into I:T:C, aborts the CALLING
#         SCRIPT whatever `|| true` it has, including from inside a subshell;
#   E8    control: an ordinary 408 timeout still returns 1 to the caller (so `|| log_warn` keeps
#         working for an environmental failure) — the abort is not a blanket exit.
# curl and the aether CLI are stubbed as shell functions; no cluster is involved.
#
# No external test runner; invoke directly:
#   bash aether/tests/integration/test/test-generation-epoch.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# A generation body shaped like ClusterGenerationResponse: the top-level epoch comes first, and a
# top-level rabiaTerm plus per-member epochs carry DIFFERENT numbers, so a whole-body grep is caught.
BODY_OK='{"epoch":{"incarnation":1,"rabiaTerm":7,"localCounter":142},"rabiaTerm":9,"mode":"NORMAL","quiescence":"QUIESCED","core":{"desiredSize":3,"members":[{"nodeId":"n1","joinedEpoch":{"incarnation":0,"rabiaTerm":2,"localCounter":5}}]}}'
BODY_NO_EPOCH='{"rabiaTerm":9,"mode":"NORMAL","core":{"desiredSize":3,"members":[{"nodeId":"n1","joinedEpoch":{"incarnation":0,"rabiaTerm":2,"localCounter":5}}]}}'

# run_probe <name> <body-json> <await-status> <cli:yes|no> <snippet>: runs <snippet> in a fresh
# bash that has sourced generation.sh with curl (and optionally the aether CLI) stubbed. Writes
# stdout/stderr to $WORK/<name>.out/.err, POSTed URLs to $WORK/<name>.posts and CLI argv to
# $WORK/<name>.cli; prints the child's exit code.
run_probe() {
    local name="$1" body="$2" status="$3" cli="$4" snippet="$5"
    : > "${WORK}/${name}.posts"; : > "${WORK}/${name}.cli"
    PROBE_BODY="$body" PROBE_STATUS="$status" PROBE_CLI="$cli" \
    PROBE_POSTS="${WORK}/${name}.posts" PROBE_CLI_LOG="${WORK}/${name}.cli" \
    INTEG_DIR="$INTEG_DIR" bash -c '
        set -uo pipefail
        export TARGET_HOST="generation-epoch-test" API_KEY="k"
        source "${INTEG_DIR}/lib/generation.sh"
        curl() {
            local a url=""
            for a in "$@"; do case "$a" in http*) url="$a" ;; esac; done
            case "$url" in
                */api/v1/cluster/generation) printf "%s" "$PROBE_BODY" ;;
                */api/v1/cluster/await-quiesced*) echo "$url" >> "$PROBE_POSTS"; printf "%s" "$PROBE_STATUS" ;;
                *) return 7 ;;
            esac
        }
        if [ "$PROBE_CLI" = "yes" ]; then
            aether() { echo "$*" >> "$PROBE_CLI_LOG"; return 1; }
        else
            command() { if [ "$1" = "-v" ] && [ "$2" = "aether" ]; then return 1; fi; builtin command "$@"; }
        fi
        sleep() { :; }
        '"$snippet" > "${WORK}/${name}.out" 2> "${WORK}/${name}.err"
    echo $?
}

# E1 — the current epoch is read from the top-level `epoch` object as I:T:C.
rc=$(run_probe e1 "$BODY_OK" 200 no 'generation_current "http://h:1"; echo; echo rc=$?')
if [ "$rc" -eq 0 ] && grep -qx '1:7:142' "${WORK}/e1.out"; then ok "E1 generation_current reads 1:7:142 from the top-level epoch object"
else fail "E1 got rc=${rc} out=$(tr '\n' '|' < "${WORK}/e1.out")"; fi

# E2 — no top-level epoch: no read at all, never the top-level rabiaTerm or a member epoch.
rc=$(run_probe e2 "$BODY_NO_EPOCH" 200 no 'if generation_current "http://h:1"; then echo READ; else echo NOREAD; fi')
if grep -qx 'NOREAD' "${WORK}/e2.out"; then ok "E2 a body without the top-level epoch yields no epoch"
else fail "E2 out=$(tr '\n' '|' < "${WORK}/e2.out")"; fi

# E3 — current+1 posts I:T:(C+1): incarnation and term kept, counter bumped; 200 returns 0.
rc=$(run_probe e3 "$BODY_OK" 200 no 'await_generation_quiesced "http://h:1" "current+1" 5; echo rc=$?')
if grep -qx 'rc=0' "${WORK}/e3.out" && grep -q 'epoch=1:7:143&' "${WORK}/e3.posts"; then ok "E3 current+1 posts epoch=1:7:143 and returns 0 on 200"
else fail "E3 out=$(tr '\n' '|' < "${WORK}/e3.out") posts=$(tr '\n' '|' < "${WORK}/e3.posts")"; fi

# E4 — a literal I:T:C passes through unchanged; the CLI (when present) is handed the same string.
rc=$(run_probe e4 "$BODY_OK" 200 yes 'await_generation_quiesced "http://h:1" "2:3:4" 5; echo rc=$?')
if grep -qx 'rc=0' "${WORK}/e4.out" && grep -q -- '--epoch 2:3:4 ' "${WORK}/e4.cli" && grep -q 'epoch=2:3:4&' "${WORK}/e4.posts"; then
    ok "E4 a literal 2:3:4 reaches both the CLI and the REST fallback unchanged"
else fail "E4 out=$(tr '\n' '|' < "${WORK}/e4.out") cli=$(tr '\n' '|' < "${WORK}/e4.cli") posts=$(tr '\n' '|' < "${WORK}/e4.posts")"; fi

# E5 — a 400 aborts the calling script even though the caller wrote `|| true`.
rc=$(run_probe e5 "$BODY_OK" 400 no 'await_generation_quiesced "http://h:1" "current+1" 5 || true; echo AFTER')
if [ "$rc" -ne 0 ] && ! grep -q AFTER "${WORK}/e5.out" && grep -q 'HARNESS BUG' "${WORK}/e5.err"; then
    ok "E5 a 400 aborts the caller despite '|| true' (rc=${rc}, named HARNESS BUG)"
else fail "E5 rc=${rc} out=$(tr '\n' '|' < "${WORK}/e5.out") err=$(tr '\n' '|' < "${WORK}/e5.err")"; fi

# E6 — the same from inside a subshell (the run-tests.sh `( await ... || log_warn ... )` shape).
rc=$(run_probe e6 "$BODY_OK" 400 no '( await_generation_quiesced "http://h:1" "current" 5 || true ); echo AFTER')
if [ "$rc" -ne 0 ] && ! grep -q AFTER "${WORK}/e6.out" && grep -q 'HARNESS BUG' "${WORK}/e6.err"; then
    ok "E6 a 400 inside a subshell still aborts the calling script (rc=${rc})"
else fail "E6 rc=${rc} out=$(tr '\n' '|' < "${WORK}/e6.out") err=$(tr '\n' '|' < "${WORK}/e6.err")"; fi

# E7 — the legacy T:C spec is refused before any request is sent, and aborts the caller.
rc=$(run_probe e7 "$BODY_OK" 200 no 'await_generation_quiesced "http://h:1" "7:142" 5 || true; echo AFTER')
if [ "$rc" -ne 0 ] && ! grep -q AFTER "${WORK}/e7.out" && [ ! -s "${WORK}/e7.posts" ] && grep -q "invalid epoch spec '7:142'" "${WORK}/e7.err"; then
    ok "E7 a legacy T:C spec aborts before any POST"
else fail "E7 rc=${rc} out=$(tr '\n' '|' < "${WORK}/e7.out") posts=$(tr '\n' '|' < "${WORK}/e7.posts")"; fi

# E8 — control: a 408 timeout is environmental; it returns 1 and the caller continues.
rc=$(run_probe e8 "$BODY_OK" 408 no 'await_generation_quiesced "http://h:1" "current+1" 5; echo rc=$?; echo AFTER')
if [ "$rc" -eq 0 ] && grep -qx 'rc=1' "${WORK}/e8.out" && grep -q AFTER "${WORK}/e8.out" && ! grep -q 'HARNESS BUG' "${WORK}/e8.err"; then
    ok "E8 control: a 408 returns 1 to the caller, no abort"
else fail "E8 rc=${rc} out=$(tr '\n' '|' < "${WORK}/e8.out") err=$(tr '\n' '|' < "${WORK}/e8.err")"; fi

echo ""
echo "  ----"
echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
