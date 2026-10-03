#!/bin/bash
#
# #1868: rolling-aether-upgrade.sh waited for a DECOMMISSIONED state the server never emits. A drain is now
# complete only when the node's OWN address refuses connections. This test sources just the wait functions from
# the script and drives them against REAL sockets: a closed port (the halted node), and a live listener that
# answers 404 (a live node relaying the leader's soft-state 404), which must NOT complete the wait.
#
# Usage: aether/script/test-rolling-aether-upgrade-drain-wait.sh   (needs bash, curl, python3)
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/rolling-aether-upgrade.sh"
FAILED=0

log_error() { echo "[script-error] $1" >&2; }

# Pull the functions under test out of the script without running its main flow.
for fn in node_endpoint wait_for_refusal; do
    body=$(sed -n "/^${fn}() {/,/^}/p" "$SCRIPT")
    if [ -z "$body" ]; then
        echo "FAIL: function $fn not found in $SCRIPT"
        exit 1
    fi
    eval "$body"
done

pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILED=1; }

free_port() {
    python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()'
}

# --- node_endpoint lookup
NODE_ENDPOINTS=" core-0=10.0.0.1:8080 core-1=10.0.0.2:8080"
[ "$(node_endpoint core-1)" = "10.0.0.2:8080" ] && pass "node_endpoint finds the address by node id" || fail "node_endpoint lookup"
[ -z "$(node_endpoint core-9)" ] && pass "node_endpoint is empty for an unknown node" || fail "node_endpoint unknown node"

# --- halted node: a closed port refuses the connection, so the wait completes
CLOSED=$(free_port)
if wait_for_refusal "127.0.0.1:$CLOSED" 10; then
    pass "a refused connection (halted node) completes the wait"
else
    fail "a closed port did not complete the wait"
fi

# --- live node answering 404: must NOT complete (control that the wait can fail at all)
DOCROOT=$(mktemp -d)
LIVE=$(free_port)
(cd "$DOCROOT" && exec python3 -m http.server "$LIVE" --bind 127.0.0.1 >/dev/null 2>&1) &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null; wait $SERVER_PID 2>/dev/null; rm -rf "$DOCROOT"' EXIT
for _ in 1 2 3 4 5 6 7 8 9 10; do
    curl -s -o /dev/null "http://127.0.0.1:$LIVE/" && break
    sleep 0.5
done
status=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$LIVE/health/ready")
[ "$status" = "404" ] && pass "control: the live listener answers 404" || fail "control: expected 404 from the live listener, got $status"

if wait_for_refusal "127.0.0.1:$LIVE" 4 2>/dev/null; then
    fail "a live node answering 404 completed the wait"
else
    pass "a live node answering 404 does NOT complete the wait"
fi

exit $FAILED
