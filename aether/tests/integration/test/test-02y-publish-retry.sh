#!/bin/bash
# test-02y-publish-retry.sh — stubs only (no cluster, no cloud). The REAL publish_marker /
# pick_publish_endpoint / record_503_wait / print_503_summary (suites/02y-stream-crash) and the REAL
# _api_call (lib/common.sh) run against a stub `curl`. Refs #1737, #1762 (s29 02y Publish_40 37/40).
#   P1  503 "not yet promoted" x3 then 200      -> ACKED in exactly 4 requests, ALL to the same endpoint,
#                                                  a WARN carries the retry count, the summary line shows 3
#   P2  500 "Publish outcome unknown"           -> exactly 1 request, not ACKED (a resend could double-publish)
#   P3  000 (pin dead) then the other endpoint  -> re-picks: 1 request to the dead pin, 1 to the other, ACKED
#   P4  503 refused forever                     -> gives up at the budget, >2 requests, all one endpoint,
#                                                  a WARN naming the budget and the retries, outcome gave-up
#   P6  PRODUCER TRIPWIRE: every alternative the suite's 503 matcher relies on must occur in the producer
#       source (StreamError.java, StreamForwardError.java) at this head; the stub refusal bodies of P1/P1c
#       are the producers' own literals read from that source, not hand-typed wording
#   P5  503 with other wording                  -> not retried, exactly 1 request
# Mutations (see the PR): retry any non-2xx reddens P2; re-pick on 503 reddens P1 (requests reach b).
#   SUITE_UNDER_TEST selects an alternate copy.
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ROOT="$(cd "${INTEG_DIR}/../../.." && pwd)"
STREAM_ERROR_SRC="${STREAM_ERROR_SRC_UNDER_TEST:-${ROOT}/aether/aether-stream/src/main/java/org/pragmatica/aether/stream/StreamError.java}"
FORWARD_ERROR_SRC="${FORWARD_ERROR_SRC_UNDER_TEST:-${ROOT}/aether/aether-stream/src/main/java/org/pragmatica/aether/stream/forward/StreamForwardError.java}"
SUITE="${SUITE_UNDER_TEST:-${INTEG_DIR}/suites/02y-stream-crash/test-stream-crash-durability.sh}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

WORK=$(mktemp -d)
trap '[ -n "${KEEP:-}" ] && echo "WORK=$WORK" >&2 || rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"
extract() { sed -n "/^$2() {/,/^}/p" "$1"; }

{
cat <<'STUB'
log_info() { :; }; log_warn() { echo "WARN $*" >&2; }; log_fail() { echo "FAIL $*"; }
API_KEY=k; CLOUD_MODE=false
MARKER_PREFIX=CRASHDUR
node_app_endpoints() { printf '%s\n' http://a http://b; }
STREAM_PUBLISH_ENDPOINT=""
STUB
extract "${INTEG_DIR}/lib/common.sh" _api_call
for f in marker_for pick_publish_endpoint publish_marker record_503_wait print_503_summary; do extract "$SUITE" "$f"; done
} > "$WORK/fns.sh"

# Stub curl. PUB=<mode>; the endpoint is the URL's host. Every POST is logged as "POST <url>".
cat > "$WORK/bin/curl" <<'STUB'
#!/bin/bash
url="${*: -1}"
echo "POST $url" >> "$CALLS"
n=$(grep -c "^POST $url" "$CALLS")
case "$PUB" in
  503then200) if [ "$n" -le 3 ]; then printf '{"detail":"%s"}\n__API_HTTP_STATUS:503__' "$REFUSAL"; else printf '{"status":"published","offset":%s}\n__API_HTTP_STATUS:200__' "$n"; fi ;;
  500) printf '{"detail":"Publish outcome unknown: the event may already be in the log (FORWARD_TIMEOUT)"}\n__API_HTTP_STATUS:500__' ;;
  dead-a) case "$url" in http://a/*) echo "curl: (7) Failed to connect"; exit 7 ;; *) printf '{"status":"published","offset":1}\n__API_HTTP_STATUS:200__' ;; esac ;;
  503forever) printf '{"detail":"%s"}\n__API_HTTP_STATUS:503__' "$REFUSAL" ;;
  503plain) printf '{"detail":"temporarily unavailable, retryable"}\n__API_HTTP_STATUS:503__' ;;
esac
STUB
chmod +x "$WORK/bin/curl"

# The producers' own literals, read from source ("%s" format holes filled).
literal_containing() {  # <file> <fixed text>: the Java string literal on the line containing it
    grep -F "$2" "$1" | head -1 | sed -E 's/^[^"]*"//; s/"[^"]*$//; s/%s/x/g; s/%d/0/g'
}
REFUSAL_A="$(literal_containing "$STREAM_ERROR_SRC" 'is not yet promoted on this node')"
REFUSAL_B="$(literal_containing "$FORWARD_ERROR_SRC" 'Remote publish retryable')"
REFUSAL_B="${REFUSAL_B%%\" + *}"; REFUSAL_B="${REFUSAL_B}x"

run_fn() {  # <label> <PUB> [budget_s] [refusal]
    ( export REFUSAL="${4:-$REFUSAL_A}" PATH="$WORK/bin:$PATH" CALLS="$WORK/calls.$1" PUB="$2" \
             PUBLISH_503_BUDGET_S="${3:-2}" PUBLISH_503_DELAY_S=0.2 PUBLISH_503_MAX_DELAY_S=0.4 \
             PUBLISH_503_LOG="$WORK/log.$1"
      : > "$CALLS"; : > "$PUBLISH_503_LOG"; source "$WORK/fns.sh"
      publish_marker 7; echo "rc=$?"; print_503_summary; echo "PIN=$STREAM_PUBLISH_ENDPOINT" ) > "$WORK/out.$1" 2> "$WORK/err.$1"
}
posts() { grep -c '^POST' "$WORK/calls.$1"; }
posts_to() { grep -c "^POST http://$2/" "$WORK/calls.$1"; }
rc() { sed -n 's/^rc=//p' "$WORK/out.$1"; }

run_fn p1 503then200 10
if [ "$(rc p1)" = "0" ] && [ "$(posts p1)" = "4" ] && [ "$(posts_to p1 a)" = "4" ]; then ok "P1 503 x3 then 200 is ACKED in exactly 4 requests, all on the same endpoint"
else fail "P1 rc=$(rc p1) posts=$(posts p1) to-a=$(posts_to p1 a)"; fi
if grep -q 'absorbed 3 503' "$WORK/err.p1" && grep -qE '^02y max-503-wait=[0-9]+s retries=3$' "$WORK/out.p1"; then ok "P1b the absorbed outage is visible: a WARN with the retry count and a max-503-wait summary line"
else fail "P1b err=$(grep absorbed "$WORK/err.p1" | head -1) out=$(grep 02y "$WORK/out.p1")"; fi

run_fn p1c 503then200 10 "$REFUSAL_B"
if [ "$(rc p1c)" = "0" ] && [ "$(posts p1c)" = "4" ]; then ok "P1c the forward producer's 'Remote publish retryable' 503 is retried the same way"
else fail "P1c rc=$(rc p1c) posts=$(posts p1c) body=[$REFUSAL_B]"; fi

run_fn p2 500
if [ "$(rc p2)" = "1" ] && [ "$(posts p2)" = "1" ]; then ok "P2 500 is not ACKED and sent exactly 1 request (never retried)"
else fail "P2 rc=$(rc p2) posts=$(posts p2)"; fi

run_fn p3 dead-a
if [ "$(rc p3)" = "0" ] && [ "$(posts_to p3 a)" = "1" ] && [ "$(posts_to p3 b)" = "1" ] && grep -q '^PIN=http://b$' "$WORK/out.p3"; then ok "P3 000 re-picks: 1 request to the dead pin, 1 to the other, ACKED"
else fail "P3 rc=$(rc p3) a=$(posts_to p3 a) b=$(posts_to p3 b) $(grep PIN "$WORK/out.p3")"; fi

run_fn p4 503forever
if [ "$(rc p4)" = "1" ] && [ "$(posts p4)" -gt 2 ] && [ "$(posts_to p4 b)" = "0" ] \
   && grep -qE 'persisted for 2s \([0-9]+ retries\)' "$WORK/err.p4" && grep -q ' gave-up$' "$WORK/log.p4"; then ok "P4 503 forever gives up at the budget ($(posts p4) requests, one endpoint) with a WARN naming budget and retries"
else fail "P4 rc=$(rc p4) posts=$(posts p4) b=$(posts_to p4 b) err=$(head -c 160 "$WORK/err.p4")"; fi

run_fn p5 503plain
if [ "$(rc p5)" = "1" ] && [ "$(posts p5)" = "1" ]; then ok "P5 a 503 without a before-write wording is not retried"
else fail "P5 rc=$(rc p5) posts=$(posts p5)"; fi

# P6: every alternative in the suite's matcher must be produced by the source at this head.
matcher=$(grep -oE "grep -qiE '[^']*Remote publish retryable[^']*'" "$SUITE" | sed -E "s/^grep -qiE '//; s/'\$//")
missing=""; n_alt=0
while IFS= read -r alt; do
    [ -n "$alt" ] || continue
    n_alt=$((n_alt + 1))
    grep -qiF -- "$alt" "$STREAM_ERROR_SRC" "$FORWARD_ERROR_SRC" || missing="${missing}[${alt}] "
done < <(printf '%s\n' "$matcher" | tr '|' '\n')
if [ "$n_alt" -ge 2 ] && [ -z "$missing" ]; then ok "P6 all ${n_alt} matcher wordings occur in the producer source"
else fail "P6 alternatives=${n_alt} absent from producers: ${missing:-none}"; fi

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
