#!/bin/bash
# test-partition-heal-detach.sh — stubs only: a FAKE `hcloud` plays Hetzner. The REAL cloud_heal_partition,
# _cloud_fw_applied_count, _cloud_fw_wait_applied and _cloud_partition_fw_name (lib/cluster.sh) run against it.
# S-triple-prime (2026-10-01, 12-network S05): `hcloud firewall delete` for the second minority node failed with
# "firewall with ID 11713049 is still in use (resource_in_use)" right after remove-from-resource: the detach ACTION had
# been accepted but applied_to had not settled. The firewall stayed APPLIED to the server, the node stayed
# partitioned for the rest of the run, and the teardown retry failed the same way.
#   H1  delete refused resource_in_use 3 times, then succeeds              -> healed, 4 delete calls, firewall gone
#   H2  applied_to clears LATE (after 4 describe polls)                    -> healed, and NO delete was attempted while
#                                                                            applied_to was non-empty (the wait, not the retry)
#   H3  applied_to never clears                                            -> fails after the budget, naming firewall id
#                                                                            and server, record file kept (driver's proof of zero)
#   H4  delete exits 0 but the firewall still exists                       -> fails (the outcome is verified, not the exit code)
#   H5  firewall already gone                                              -> ok (idempotent), no detach attempted
#   H6  resource_in_use forever                                            -> fails at the budget, id+server named
#   A1  create path: applied_to shows the server only after 3 polls        -> _cloud_fw_wait_applied waits and succeeds
#   A2  create path: applied_to never shows it                             -> times out (the caller warns, not fatal)
#   Mutations: no wait (H2 red), no verify (H4 red), no resource_in_use retry (H1 red).   LIB_UNDER_TEST selects a copy.
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LIB="${LIB_UNDER_TEST:-${INTEG_DIR}/lib/cluster.sh}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

WORK=$(mktemp -d)
trap '[ -n "${KEEP:-}" ] && echo "WORK=$WORK" >&2 || rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"
extract() { sed -n "/^$2() {/,/^}/p" "$1"; }

{
cat <<'STUB'
log_info() { echo "INFO $*"; }; log_warn() { echo "WARN $*"; }; log_fail() { echo "FAIL $*"; }
cloud_server_id() { echo 168166138; }
CLOUD_PROVIDER=hetzner
STUB
for f in _cloud_partition_fw_name _cloud_fw_applied_count _cloud_fw_wait_applied cloud_heal_partition; do extract "$LIB" "$f"; done
} > "$WORK/fns.sh"

# Fake hcloud. State in $FW/: exists (1/0), applied (count), clear_after (describe-json polls until applied -> 0, set
# by remove-from-resource from $DETACH_LAG), inuse_n (delete refusals regardless of state), calls (log),
# DELETE_NOOP / APPLY_AFTER via env. Every call is logged to $FW/calls.
cat > "$WORK/bin/hcloud" <<'STUB'
#!/bin/bash
echo "$*" >> "$FW/calls"
get() { cat "$FW/$1" 2>/dev/null || echo "${2:-0}"; }
set_() { echo "$2" > "$FW/$1"; }
case "$1 $2" in
  "firewall describe")
    if [ "$(get exists 1)" != "1" ]; then echo "hcloud: firewall not found (not_found)" >&2; exit 1; fi
    if printf '%s' "$*" | grep -q -- '-o json'; then
        ca=$(get clear_after -1)
        if [ "$ca" -ge 0 ] 2>/dev/null; then
            if [ "$ca" -eq 0 ]; then set_ applied 0; set_ clear_after -1; else set_ clear_after $((ca - 1)); fi
        fi
        aa=$(get apply_after -1)
        if [ "$aa" -ge 0 ] 2>/dev/null; then
            if [ "$aa" -eq 0 ]; then set_ applied 1; set_ apply_after -1; else set_ apply_after $((aa - 1)); fi
        fi
        n=$(get applied 0); items=""; i=0
        while [ "$i" -lt "$n" ]; do items="${items}${items:+,}{\"type\":\"server\",\"server\":{\"id\":$((168166138 + i))}}"; i=$((i + 1)); done
        printf '{"id":11713049,"name":"fw","rules":[{"direction":"in"}],"applied_to":[%s]}' "$items"
    else
        echo 11713049
    fi ;;
  "firewall remove-from-resource")
    set_ clear_after "${DETACH_LAG:-0}"; exit 0 ;;
  "firewall delete")
    if [ "$(get exists 1)" != "1" ]; then echo "hcloud: firewall not found (not_found)" >&2; exit 1; fi
    if [ "$(get applied 0)" -gt 0 ] 2>/dev/null; then
        echo "delete-while-applied" >> "$FW/events"
        echo "hcloud: firewall with ID 11713049 is still in use (resource_in_use)" >&2; exit 1
    fi
    iu=$(get inuse_n 0)
    if [ "$iu" -gt 0 ] 2>/dev/null; then set_ inuse_n $((iu - 1)); echo "hcloud: firewall with ID 11713049 is still in use (resource_in_use)" >&2; exit 1; fi
    [ -n "${DELETE_NOOP:-}" ] || set_ exists 0
    exit 0 ;;
  *) exit 0 ;;
esac
STUB
chmod +x "$WORK/bin/hcloud"

run() {  # <label> <applied> <exists> [VAR=value ...]   -> rc in rc.<label>, output in out.<label>, state in fw.<label>/
    local label="$1" applied="$2" exists="$3"; shift 3
    local fw="$WORK/fw.$label"; mkdir -p "$fw"; : > "$fw/calls"; : > "$fw/events"
    echo "$applied" > "$fw/applied"; echo "$exists" > "$fw/exists"; echo "${INUSE_N:-0}" > "$fw/inuse_n"
    ( export FW="$fw" PATH="$WORK/bin:$PATH" CLOUD_FW_POLL_S=0.2 CLOUD_HEAL_TIMEOUT_S="${HEAL_BUDGET:-3}" CLOUD_HEAL_RETRY_DELAY_S=0.2 "$@"
      source "$WORK/fns.sh"; : > /tmp/aether-partition-fw-nodeX.id; cloud_heal_partition nodeX ) > "$WORK/out.$label" 2>&1
    echo $? > "$WORK/rc.$label"
}
deletes() { grep -c '^firewall delete' "$WORK/fw.$1/calls"; }

INUSE_N=3 run h1 0 1
if [ "$(cat "$WORK/rc.h1")" = "0" ] && [ "$(deletes h1)" = "4" ] && [ "$(cat "$WORK/fw.h1/exists")" = "0" ] && grep -q 'resource_in_use; retrying' "$WORK/out.h1"; then
    ok "H1 resource_in_use x3 then success: healed after 4 delete calls, firewall gone, retries logged"
else fail "H1 rc=$(cat "$WORK/rc.h1") deletes=$(deletes h1) exists=$(cat "$WORK/fw.h1/exists") out=$(tail -2 "$WORK/out.h1" | cut -c1-120 | tr '\n' '|')"; fi

run h2 1 1 DETACH_LAG=4
if [ "$(cat "$WORK/rc.h2")" = "0" ] && [ "$(cat "$WORK/fw.h2/exists")" = "0" ] && [ ! -s "$WORK/fw.h2/events" ] && [ "$(deletes h2)" = "1" ]; then
    ok "H2 applied_to clears late (4 polls): healed with ONE delete, none attempted while applied_to was non-empty"
else fail "H2 rc=$(cat "$WORK/rc.h2") deletes=$(deletes h2) delete-while-applied=$(grep -c . "$WORK/fw.h2/events") exists=$(cat "$WORK/fw.h2/exists")"; fi

run h3 1 1 DETACH_LAG=100000
if [ "$(cat "$WORK/rc.h3")" = "1" ] && grep -q 'id 11713049' "$WORK/out.h3" && grep -q '168166138' "$WORK/out.h3" && grep -q 'PARTITIONED' "$WORK/out.h3" \
   && [ -f /tmp/aether-partition-fw-nodeX.id ] && [ "$(deletes h3)" = "0" ]; then
    ok "H3 applied_to never clears: fails after the budget naming firewall id and server, record kept, no delete raced"
else fail "H3 rc=$(cat "$WORK/rc.h3") deletes=$(deletes h3) out=$(tail -1 "$WORK/out.h3" | cut -c1-160)"; fi

run h4 0 1 DELETE_NOOP=1
if [ "$(cat "$WORK/rc.h4")" = "1" ] && grep -q 'reported success but the firewall still exists' "$WORK/out.h4"; then ok "H4 delete exit 0 with the firewall still present fails (outcome verified)"
else fail "H4 rc=$(cat "$WORK/rc.h4") out=$(tail -1 "$WORK/out.h4" | cut -c1-140)"; fi

run h5 0 0
if [ "$(cat "$WORK/rc.h5")" = "0" ] && ! grep -q 'remove-from-resource' "$WORK/fw.h5/calls" && grep -q 'already healed' "$WORK/out.h5"; then ok "H5 an already-healed firewall is a no-op success (no detach attempted)"
else fail "H5 rc=$(cat "$WORK/rc.h5")"; fi

INUSE_N=100000 HEAL_BUDGET=2 run h6 0 1
if [ "$(cat "$WORK/rc.h6")" = "1" ] && grep -q 'still resource_in_use after 2s' "$WORK/out.h6" && grep -q 'id 11713049' "$WORK/out.h6" && grep -q '168166138' "$WORK/out.h6"; then
    ok "H6 resource_in_use forever fails at the budget, naming the firewall id and server"
else fail "H6 rc=$(cat "$WORK/rc.h6") out=$(tail -1 "$WORK/out.h6" | cut -c1-160)"; fi

# A1/A2: the create path's wait
a_run() {  # <label> <apply_after> <budget>
    local fw="$WORK/fw.$1"; mkdir -p "$fw"; : > "$fw/calls"; echo 0 > "$fw/applied"; echo 1 > "$fw/exists"; echo "$2" > "$fw/apply_after"
    ( export FW="$fw" PATH="$WORK/bin:$PATH" CLOUD_FW_POLL_S=0.2
      source "$WORK/fns.sh"; _cloud_fw_wait_applied fw applied "$3" ) > "$WORK/out.$1" 2>&1; echo $? > "$WORK/rc.$1"
}
a_run a1 3 5
if [ "$(cat "$WORK/rc.a1")" = "0" ]; then ok "A1 create path: applied_to shows the server after 3 polls; the wait succeeds"; else fail "A1 rc=$(cat "$WORK/rc.a1")"; fi
a_run a2 100000 1
if [ "$(cat "$WORK/rc.a2")" = "1" ]; then ok "A2 create path: applied_to never shows the server; the wait times out (the caller warns)"; else fail "A2 rc=$(cat "$WORK/rc.a2")"; fi
rm -f /tmp/aether-partition-fw-nodeX.id

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
