#!/bin/bash
# generation.sh — ClusterGeneration-based deterministic quiescence helpers.
#
# Replaces the old retry/sleep/self_heal machinery with server-side synchronous
# waits using the public endpoints introduced in commit 9a8f6ec7b:
#   - GET  /api/v1/cluster/generation            (current snapshot)
#   - POST /api/v1/cluster/await-quiesced        (blocking epoch-gated wait)
#
# Preferred invocation is through `aether cluster await-quiesced` per user
# feedback — fall back to raw curl only when the CLI is unavailable.
#
# Semantics (see aether/docs/specs/cluster-generation-spec.md §14):
#   - epoch = "incarnation:term:counter" (e.g. "1:7:142"), cluster incarnation first (#1529).
#   - A 400 from the barrier routes, or an epoch this file cannot build, is a HARNESS BUG, never
#     an environmental failure: it aborts the calling script whatever `|| true` the caller has
#     (_generation_harness_bug). A barrier that silently no-ops makes every later result meaningless.
#   - "quiesced" means observedEpoch >= requested AND snapshot.quiescence==QUIESCED.
#   - Server endpoint polls internally at 200ms intervals up to timeout (max 120s).

LIB_DIR_GENERATION="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${LIB_DIR_GENERATION}/common.sh"

# fd 7 = the script's stderr as it was when this library loaded. _generation_harness_bug writes there, so
# its message reaches the operator even when the caller redirects the barrier's own output
# (`await_generation_quiesced ... >/dev/null 2>&1 || true`, cluster.sh). fds 8 and 9 are the stub
# harnesses' tick FIFOs; nothing else in this harness uses 7.
exec 7>&2

# ---------------------------------------------------------------------------
# generation_current [endpoint]
#
# Prints the current epoch as "I:T:C" read from the top-level `epoch` object of
# GET /api/v1/cluster/generation (never from the per-member/partition epochs).
# If the node has no snapshot yet (epoch==null) prints empty + returns 1.
# endpoint defaults to ${CLUSTER_ENDPOINT} (per-suite scoped mgmt endpoint).
# ---------------------------------------------------------------------------
generation_current() {
    local endpoint="${1:-$(_resolve_live_endpoint)}"
    local response
    response=$(curl -sf -m 5 -H "X-API-Key: ${API_KEY}" \
        "${endpoint}/api/v1/cluster/generation" 2>/dev/null) || return 1
    # Scope the read to the top-level `epoch` object: the response also carries a top-level
    # rabiaTerm and per-member joinedEpoch/lastSeenEpoch objects, which a first-match grep over
    # the whole body would pick up whenever `epoch` is absent.
    local epoch_obj incarnation term counter
    epoch_obj=$(printf '%s' "$response" | grep -oE '"epoch"[[:space:]]*:[[:space:]]*\{[^}]*\}' | head -1)
    incarnation=$(_epoch_field "$epoch_obj" incarnation)
    term=$(_epoch_field "$epoch_obj" rabiaTerm)
    counter=$(_epoch_field "$epoch_obj" localCounter)
    if [ -z "$incarnation" ] || [ -z "$term" ] || [ -z "$counter" ]; then
        return 1
    fi
    printf '%s:%s:%s' "$incarnation" "$term" "$counter"
}

# ---------------------------------------------------------------------------
# await_generation_quiesced [endpoint] [epoch] [timeout_seconds]
#
# Blocks until the queried node reports observedEpoch >= requested AND local
# snapshot quiescence == QUIESCED. Delegates polling to the server.
#
# Arguments:
#   endpoint        — management endpoint (default: ${CLUSTER_ENDPOINT})
#   epoch           — "I:T:C" form; "current" reads current; "current+N" advances
#                     current's COUNTER by N (incarnation and term kept). Default: "current+1".
#   timeout_seconds — default 30, server caps at 120.
# Exit codes:
#   0 — quiesced at-or-beyond epoch within timeout
#   1 — server returned 408 timeout or network error
#   2 — the current epoch could not be read (unreachable, not a quiescence failure)
#   never returns on a malformed epoch or a 400: the calling script is aborted
# ---------------------------------------------------------------------------
await_generation_quiesced() {
    local endpoint="${1:-$(_resolve_live_endpoint)}"
    local epoch="${2:-current+1}"
    local timeout="${3:-30}"
    # Same scaling as wait_for — cloud's higher inter-node latency stretches consensus rounds.
    timeout=$((timeout * ${TIMEOUT_SCALE:-1}))

    local target_epoch resolve_rc
    target_epoch=$(_resolve_epoch "$endpoint" "$epoch")
    resolve_rc=$?
    if [ "$resolve_rc" -eq 3 ]; then
        _generation_harness_bug "await_generation_quiesced: invalid epoch spec '${epoch}' (expected I:T:C, current, or current+N)"
    fi
    if [ "$resolve_rc" -ne 0 ]; then
        # rc=2 (distinct from the 408-timeout rc=1): we could not even READ the
        # current generation epoch — the endpoint is unreachable/unable to serve the
        # leader-bound /api/v1/cluster/generation route. Callers must NOT report this as
        # "did not quiesce" (the #126 misdiagnosis): the cluster may be perfectly
        # quiesced on a live node we never reached.
        log_warn "await_generation_quiesced: could not read generation epoch from ${endpoint} (cluster unreachable — NOT a quiescence failure)"
        return 2
    fi

    local host_port="${endpoint#http://}"
    host_port="${host_port#https://}"

    local start_ns
    start_ns=$(_now_ms)
    log_info "await_generation_quiesced target=${target_epoch} timeout=${timeout}s endpoint=${endpoint}"

    # Preferred: aether CLI
    if command -v aether >/dev/null 2>&1; then
        aether -c "$host_port" --api-key "${API_KEY}" cluster await-quiesced \
            --epoch "$target_epoch" --timeout "${timeout}s" >/dev/null 2>&1
        local rc=$?
        local elapsed=$(( $(_now_ms) - start_ns ))
        if [ "$rc" -eq 0 ]; then
            log_pass "quiesced at ${target_epoch} (${elapsed}ms)"
            return 0
        fi
        # Why: $? after `fi` with no else is 0 when the if-body didn't run —
        # capture rc immediately after the command or this log always reads "rc=0".
        log_warn "await-quiesced CLI rc=${rc} after ${elapsed}ms — falling back to REST"
    fi

    # Fallback: direct REST POST. Server caps internally at 120s (MAX_TIMEOUT in
    # ClusterAwaitQuiescedRoute) so curl needs to outlast min(timeout,120s)+slack.
    local server_budget=$(( timeout > 120 ? 120 : timeout ))
    local curl_budget=$(( server_budget + 10 ))
    local http_status
    http_status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST -H "X-API-Key: ${API_KEY}" \
        -m "${curl_budget}" \
        "${endpoint}/api/v1/cluster/await-quiesced?epoch=${target_epoch}&timeout=${timeout}s" 2>/dev/null)
    local elapsed=$(( $(_now_ms) - start_ns ))
    if [ "$http_status" = "200" ]; then
        log_pass "quiesced at ${target_epoch} (${elapsed}ms)"
        return 0
    fi
    if [ "$http_status" = "400" ]; then
        _generation_harness_bug "await-quiesced rejected target=${target_epoch} with 400 (the route requires incarnation:term:counter)"
    fi
    # Library function: caller decides via return code whether timeout is fatal.
    # Print as warn (not fail) so `|| log_warn` callers don't get spurious [FAIL] noise.
    log_warn "await-quiesced status=${http_status} after ${elapsed}ms (target=${target_epoch})"
    return 1
}

# ---------------------------------------------------------------------------
# generation_quiesce_now [endpoint] [timeout_seconds]
#
# Convenience wrapper: read current, advance by 1, wait. Useful as a barrier
# after a disruptive test — the next generation strictly succeeds the state
# captured at call time, so membership churn committed since then is fenced.
# ---------------------------------------------------------------------------
generation_quiesce_now() {
    local endpoint="${1:-$(_resolve_live_endpoint)}"
    local timeout="${2:-30}"
    await_generation_quiesced "$endpoint" "current+1" "$timeout"
}

# ---------------------------------------------------------------------------
# Timing aggregator (optional — used by run-tests.sh).
#
# Call sites append "${label}=${millis}" records to ${QUIESCED_TIMINGS_FILE}
# so the runner can surface per-suite await durations in the summary.
# ---------------------------------------------------------------------------
record_quiesced_timing() {
    local label="$1" millis="$2"
    if [ -n "${QUIESCED_TIMINGS_FILE:-}" ]; then
        printf '%s=%s\n' "$label" "$millis" >> "$QUIESCED_TIMINGS_FILE"
    fi
}

# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

# Resolve "current", "current+N", or literal "I:T:C" against the live snapshot.
# Returns 3 for a spec that is not one of those forms (a harness bug; the caller aborts).
# Retries up to ~10s if the snapshot is temporarily missing (e.g., during leader
# transition right after a destructive test) so the runner doesn't false-abort.
_resolve_epoch() {
    local endpoint="$1" spec="$2"
    case "$spec" in
        current)
            _resolve_with_retry "$endpoint"
            ;;
        current+*)
            local bump="${spec#current+}"
            local now
            now=$(_resolve_with_retry "$endpoint") || return 1
            case "$bump" in ''|*[!0-9]*) return 3 ;; esac
            local incarnation="${now%%:*}" rest="${now#*:}"
            local term="${rest%%:*}" counter="${rest#*:}"
            printf '%s:%s:%s' "$incarnation" "$term" "$((counter + bump))"
            ;;
        *)
            _is_epoch_string "$spec" || return 3
            printf '%s' "$spec"
            ;;
    esac
}

# _epoch_field <json-object> <name>: the unsigned integer value of <name> in <json-object>.
_epoch_field() {
    printf '%s' "$1" | grep -oE "\"$2\"[[:space:]]*:[[:space:]]*[0-9]+" | head -1 | grep -oE '[0-9]+$'
}

# _is_epoch_string <s>: true for exactly three unsigned integers joined by ':' (I:T:C).
_is_epoch_string() {
    [[ "$1" =~ ^[0-9]+:[0-9]+:[0-9]+$ ]]
}

# _generation_harness_bug <message>: aborts the calling SCRIPT, not just this function, and says why on fd 7
# (the load-time stderr), which a caller's `>/dev/null 2>&1` cannot silence.
# A malformed barrier request is a harness bug; returning non-zero would be swallowed by the
# `|| true` / `|| log_warn` most callers wrap the barrier in. `exit` ends the current shell;
# inside a subshell ( ... ) or $( ... ) that is only the subshell, so the script's own pid
# ($$ is not updated in subshells) is sent TERM first.
_generation_harness_bug() {
    local message="[FAIL]  HARNESS BUG (generation barrier): $1 -- aborting the suite: a barrier that no-ops invalidates every later result"
    { echo "$message" >&7; } 2>/dev/null || echo "$message" >&2
    if [ "${BASH_SUBSHELL:-0}" -gt 0 ]; then
        kill -TERM "$$" 2>/dev/null
    fi
    exit 3
}

_resolve_with_retry() {
    local endpoint="$1"
    local now
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        now=$(generation_current "$endpoint") && { printf '%s' "$now"; return 0; }
        sleep 1
    done
    return 1
}

# Milliseconds since epoch — portable between GNU and BSD date.
_now_ms() {
    # Probe for GNU %3N support; BSD date returns literal "%3N" (or a trailing "N").
    local raw
    raw=$(date +%s%3N 2>/dev/null)
    case "$raw" in
        *[!0-9]*) echo $(( $(date +%s) * 1000 )) ;;
        *)        printf '%s' "$raw" ;;
    esac
}
