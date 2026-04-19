#!/bin/bash
# generation.sh — ClusterGeneration-based deterministic quiescence helpers.
#
# Replaces the old retry/sleep/self_heal machinery with server-side synchronous
# waits using the public endpoints introduced in commit 9a8f6ec7b:
#   - GET  /api/cluster/generation            (current snapshot)
#   - POST /api/cluster/await-quiesced        (blocking epoch-gated wait)
#
# Preferred invocation is through `aether cluster await-quiesced` per user
# feedback — fall back to raw curl only when the CLI is unavailable.
#
# Semantics (see aether/docs/specs/cluster-generation-spec.md §14):
#   - epoch = "term:counter" (e.g. "7:142").
#   - "quiesced" means observedEpoch >= requested AND snapshot.quiescence==QUIESCED.
#   - Server endpoint polls internally at 200ms intervals up to timeout (max 120s).

LIB_DIR_GENERATION="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${LIB_DIR_GENERATION}/common.sh"

# ---------------------------------------------------------------------------
# generation_current [endpoint]
#
# Prints the current epoch as "T:C" read from GET /api/cluster/generation.
# If the node has no snapshot yet (epoch==null) prints empty + returns 1.
# endpoint defaults to ${CLUSTER_ENDPOINT} (per-suite scoped mgmt endpoint).
# ---------------------------------------------------------------------------
generation_current() {
    local endpoint="${1:-${CLUSTER_ENDPOINT}}"
    local response
    response=$(curl -sf -m 5 -H "X-API-Key: ${API_KEY}" \
        "${endpoint}/api/cluster/generation" 2>/dev/null) || return 1
    # Extract nested epoch.rabiaTerm / epoch.localCounter.
    local term counter
    term=$(printf '%s' "$response" | grep -oE '"rabiaTerm"[[:space:]]*:[[:space:]]*[0-9]+' | head -1 | grep -oE '[0-9]+$')
    counter=$(printf '%s' "$response" | grep -oE '"localCounter"[[:space:]]*:[[:space:]]*[0-9]+' | head -1 | grep -oE '[0-9]+$')
    if [ -z "$term" ] || [ -z "$counter" ]; then
        return 1
    fi
    printf '%s:%s' "$term" "$counter"
}

# ---------------------------------------------------------------------------
# await_generation_quiesced [endpoint] [epoch] [timeout_seconds]
#
# Blocks until the queried node reports observedEpoch >= requested AND local
# snapshot quiescence == QUIESCED. Delegates polling to the server.
#
# Arguments:
#   endpoint        — management endpoint (default: ${CLUSTER_ENDPOINT})
#   epoch           — "T:C" form; "current" reads current; "current+N" advances
#                     current by N. Default: "current+1".
#   timeout_seconds — default 30, server caps at 120.
# Exit codes:
#   0 — quiesced at-or-beyond epoch within timeout
#   1 — server returned 408 timeout or network error
# ---------------------------------------------------------------------------
await_generation_quiesced() {
    local endpoint="${1:-${CLUSTER_ENDPOINT}}"
    local epoch="${2:-current+1}"
    local timeout="${3:-30}"

    local target_epoch
    target_epoch=$(_resolve_epoch "$endpoint" "$epoch") || return 1

    local host_port="${endpoint#http://}"
    host_port="${host_port#https://}"

    local start_ns
    start_ns=$(_now_ms)
    log_info "await_generation_quiesced target=${target_epoch} timeout=${timeout}s endpoint=${endpoint}"

    # Preferred: aether CLI
    if command -v aether >/dev/null 2>&1; then
        if aether -c "$host_port" --api-key "${API_KEY}" cluster await-quiesced \
            --epoch "$target_epoch" --timeout "${timeout}s" >/dev/null 2>&1; then
            local elapsed=$(( $(_now_ms) - start_ns ))
            log_pass "quiesced at ${target_epoch} (${elapsed}ms)"
            return 0
        fi
        local rc=$?
        # CLI failed; fall through to raw HTTP only on unknown-subcommand error.
        # Any real rejection (408) maps to exit!=0 — surface that.
        local elapsed=$(( $(_now_ms) - start_ns ))
        log_warn "await-quiesced CLI rc=${rc} after ${elapsed}ms — falling back to REST"
    fi

    # Fallback: direct REST POST
    local http_status
    http_status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST -H "X-API-Key: ${API_KEY}" \
        -m $(( timeout + 5 )) \
        "${endpoint}/api/cluster/await-quiesced?epoch=${target_epoch}&timeout=${timeout}s" 2>/dev/null)
    local elapsed=$(( $(_now_ms) - start_ns ))
    if [ "$http_status" = "200" ]; then
        log_pass "quiesced at ${target_epoch} (${elapsed}ms)"
        return 0
    fi
    log_fail "await-quiesced status=${http_status} after ${elapsed}ms (target=${target_epoch})"
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
    local endpoint="${1:-${CLUSTER_ENDPOINT}}"
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

# Resolve "current", "current+N", or literal "T:C" against the live snapshot.
_resolve_epoch() {
    local endpoint="$1" spec="$2"
    case "$spec" in
        current)
            generation_current "$endpoint"
            ;;
        current+*)
            local bump="${spec#current+}"
            local now
            now=$(generation_current "$endpoint") || return 1
            local term="${now%%:*}" counter="${now##*:}"
            printf '%s:%s' "$term" "$((counter + bump))"
            ;;
        *:*)
            printf '%s' "$spec"
            ;;
        *)
            log_fail "await_generation_quiesced: invalid epoch '${spec}' (expected T:C, current, or current+N)"
            return 1
            ;;
    esac
}

# Milliseconds since epoch — portable between GNU and BSD date.
_now_ms() {
    if date +%N 2>/dev/null | grep -q '^[0-9]'; then
        # GNU date supports %N (nanoseconds)
        local raw
        raw=$(date +%s%3N 2>/dev/null)
        if [ -n "$raw" ] && [ "${raw: -3}" != "3N" ]; then
            printf '%s' "$raw"
            return 0
        fi
    fi
    # Fallback: seconds*1000 (BSD date)
    echo $(( $(date +%s) * 1000 ))
}
