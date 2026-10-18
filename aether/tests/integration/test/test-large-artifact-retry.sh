#!/bin/bash
# test-large-artifact-retry.sh — stubs only (no cluster, no cloud). The REAL curl_http_status and the REAL
# push_and_verify_size (suites/09-artifacts/test-large-artifact.sh) run against a stub `curl`. Refs #2191.
#   T1  transport failure (exit 7, status 000) twice, then 200 -> the push passes in exactly 3 calls
#   T2  a 503 answer                                          -> returned at once, exactly 1 call, no retry
#   T3  a 404 answer                                          -> returned at once, exactly 1 call, no retry
#   T4  transport failure forever                             -> 000 after the attempt budget, every exit code logged
#   T5  every curl call carries --connect-timeout and --max-time
#   T6  a lost link on the resolve GET of push_and_verify_size is retried and the checksum still matches
#   T7  a lost link on the PUT is retried the same way
# Mutations (see the PR): no retry reddens T1/T4/T6/T7; retry on any nonzero status reddens T2/T3; dropping the timeouts
# reddens T5. SUITE_UNDER_TEST selects an alternate copy.
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SUITE="${SUITE_UNDER_TEST:-${INTEG_DIR}/suites/09-artifacts/test-large-artifact.sh}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

WORK=$(mktemp -d)
trap '[ -n "${KEEP:-}" ] && echo "WORK=$WORK" >&2 || rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"
extract() { sed -n "/^$2() {/,/^}/p" "$1"; }

{
cat <<'STUB'
log_info() { :; }; log_warn() { echo "WARN $*" >&2; }; log_fail() { echo "FAIL $*"; }; log_pass() { echo "PASS $*"; }
assert_eq() { if [ "$1" = "$2" ]; then log_pass "$3"; else log_fail "$3: got $1 want $2"; return 1; fi; }
API_KEY=k; CLUSTER_ENDPOINT=http://stub; ARTIFACT_BASE=large-artifact-test
STUB
extract "$SUITE" curl_http_status
extract "$SUITE" push_and_verify_size
} > "$WORK/fns.sh"

# Stub curl. MODE=<mode>; every call is logged with its full argument list. The GET copies the pushed bytes so the checksum matches.
cat > "$WORK/bin/curl" <<'STUB'
#!/bin/bash
echo "$*" >> "$CALLS"
n=$(wc -l < "$CALLS" | tr -d ' ')
out=""; prev=""
for a in "$@"; do [ "$prev" = "-o" ] && out="$a"; prev="$a"; done
case "$MODE" in
  lost2then200) if [ "$n" -le 2 ]; then printf '000'; exit 7; fi; printf '200' ;;
  503)          printf '503' ;;
  404)          printf '404' ;;
  lostforever)  printf '000'; exit 28 ;;
  ok)           [ -n "$out" ] && [ "$out" != /dev/null ] && cp "$PUSHED" "$out"; printf '200' ;;
  putlost1)     case "$*" in *"-X PUT"*) if [ ! -e "$WORK/puts" ]; then echo 1 > "$WORK/puts"; printf '000'; exit 55; fi; printf '200' ;; *) [ -n "$out" ] && cp "$PUSHED" "$out"; printf '200' ;; esac ;;
  getlost1)     case "$*" in *"-X PUT"*) printf '200' ;; *) if [ ! -e "$WORK/gets" ]; then echo 1 > "$WORK/gets"; printf '000'; exit 56; fi; [ -n "$out" ] && cp "$PUSHED" "$out"; printf '200' ;; esac ;;
esac
STUB
chmod +x "$WORK/bin/curl"

run_fn() {  # <label> <MODE> <expression to run after sourcing>
    ( export PATH="$WORK/bin:$PATH" CALLS="$WORK/calls.$1" MODE="$2" WORK \
             LARGE_ARTIFACT_RETRY_DELAY_S=0 LARGE_ARTIFACT_CURL_ATTEMPTS=3
      : > "$CALLS"; source "$WORK/fns.sh"; eval "$3" ) > "$WORK/out.$1" 2> "$WORK/err.$1"
    echo $? > "$WORK/rc.$1"
}
calls() { wc -l < "$WORK/calls.$1" | tr -d ' '; }

run_fn t1 lost2then200 'curl_http_status /dev/null -X PUT http://stub/x'
if [ "$(cat "$WORK/out.t1")" = "200" ] && [ "$(calls t1)" = "3" ]; then ok "T1 two lost links then 200 returns 200 in exactly 3 calls"
else fail "T1 out=$(cat "$WORK/out.t1") calls=$(calls t1)"; fi

run_fn t2 503 'curl_http_status /dev/null -X PUT http://stub/x'
if [ "$(cat "$WORK/out.t2")" = "503" ] && [ "$(calls t2)" = "1" ]; then ok "T2 a 503 answer is returned at once, no retry"
else fail "T2 out=$(cat "$WORK/out.t2") calls=$(calls t2)"; fi

run_fn t3 404 'curl_http_status /dev/null http://stub/x'
if [ "$(cat "$WORK/out.t3")" = "404" ] && [ "$(calls t3)" = "1" ]; then ok "T3 a 404 answer is returned at once, no retry"
else fail "T3 out=$(cat "$WORK/out.t3") calls=$(calls t3)"; fi

run_fn t4 lostforever 'curl_http_status /dev/null http://stub/x'
if [ "$(cat "$WORK/out.t4")" = "000" ] && [ "$(calls t4)" = "3" ] && [ "$(grep -c 'exit code 28' "$WORK/err.t4")" = "3" ]; then ok "T4 a link lost forever ends at 000 after 3 calls, curl's exit code logged each time"
else fail "T4 out=$(cat "$WORK/out.t4") calls=$(calls t4) err=$(head -c 160 "$WORK/err.t4")"; fi

run_fn t5 ok 'curl_http_status /dev/null http://stub/x'
if grep -q -- '--connect-timeout' "$WORK/calls.t5" && grep -q -- '--max-time' "$WORK/calls.t5"; then ok "T5 every call is bounded by --connect-timeout and --max-time"
else fail "T5 args: $(cat "$WORK/calls.t5")"; fi

run_fn t6 getlost1 'TMPDIR='"$WORK"'; PUSHED=$TMPDIR/large-artifact-push-1.0.64.bin; export PUSHED; rm -f "$WORK/gets"; push_and_verify_size 1.0.64 64'
if [ "$(cat "$WORK/rc.t6")" = "0" ] && grep -q 'PASS SHA-256 match' "$WORK/out.t6" && grep -q 'exit code 56' "$WORK/err.t6"; then ok "T6 a lost link on the resolve GET is retried inside push_and_verify_size and the checksum still matches"
else fail "T6 rc=$(cat "$WORK/rc.t6") out=$(head -c 200 "$WORK/out.t6") err=$(head -c 200 "$WORK/err.t6")"; fi

run_fn t7 putlost1 'TMPDIR='"$WORK"'; PUSHED=$TMPDIR/large-artifact-push-1.0.64.bin; export PUSHED; rm -f "$WORK/puts"; push_and_verify_size 1.0.64 64'
if [ "$(cat "$WORK/rc.t7")" = "0" ] && grep -q 'PASS SHA-256 match' "$WORK/out.t7" && grep -q 'exit code 55' "$WORK/err.t7"; then ok "T7 a lost link on the PUT is retried inside push_and_verify_size"
else fail "T7 rc=$(cat "$WORK/rc.t7") out=$(head -c 200 "$WORK/out.t7") err=$(head -c 200 "$WORK/err.t7")"; fi

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
