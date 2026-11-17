#!/usr/bin/env bash
# Fail-closed checks for run-acceptance.sh: each case must exit non-zero with its message and never reach the suite. Needs docker on PATH
# (the runner checks it first) but starts no container: every case is refused before the database is created.
#   terra/conduit-acceptance/check-fail-closed.sh
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/conduit-fc.XXXXXX")"
LISTENER=""
trap '[ -z "$LISTENER" ] || kill "$LISTENER" 2>/dev/null; rm -rf "$TMP"' EXIT
failures=0

expect_refusal() { # name, expected exit, expected message fragment, command...
  local name="$1" want="$2" msg="$3"; shift 3
  local out rc
  out="$("$@" 2>&1)"; rc=$?
  if [ "$rc" = "$want" ] && grep -q -- "$msg" <<<"$out"; then echo "PASS  $name (exit $rc)"
  else echo "FAIL  $name (exit $rc, wanted $want with \"$msg\"); output: $out" >&2; failures=$(( failures + 1 )); fi
}

mkdir -p "$TMP/dist/lib"
expect_refusal "missing distribution" 2 "no distribution" env DIST="$TMP/nowhere" WORK="$TMP/w1" "$HERE/run-acceptance.sh"

# A listener on a port the runner is told to use: the startup poll must never get to accept it.
BUSY="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
python3 -c 'import socket,sys,time; s=socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(("127.0.0.1",int(sys.argv[1]))); s.listen(); time.sleep(120)' "$BUSY" &
LISTENER=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do python3 -c 'import socket,sys; socket.create_connection(("127.0.0.1",int(sys.argv[1])),1)' "$BUSY" 2>/dev/null && break; sleep 1; done
expect_refusal "busy APP_PORT" 2 "already in use" env DIST="$TMP/dist" APP_PORT="$BUSY" WORK="$TMP/w2" "$HERE/run-acceptance.sh"

[ "$failures" = 0 ] || { echo "RESULT: FAIL ($failures)"; exit 1; }
echo "RESULT: PASS"
