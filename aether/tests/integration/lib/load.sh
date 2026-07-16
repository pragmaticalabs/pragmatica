#!/bin/bash
# load.sh — Load generation helpers for Aether integration tests (curl-based)

LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${LIB_DIR}/common.sh"

# ---------------------------------------------------------------------------
# Background load generation
# ---------------------------------------------------------------------------
LOAD_PIDS=()

# Start background HTTP load against the app endpoint
# Usage: start_load <rps> <duration_seconds> <method> <path> [body] [reresolve_coords]
#
# OWNER PINNING (cloud): APP_ENDPOINT points at ONE slice-owner VM (set by
# retarget_app_endpoint_to_active_slice — the harness has no load balancer). If a
# disruption test kills/replaces that owner mid-window, every subsequent tick gets
# status 000 (connect failure) until APP_ENDPOINT is retargeted, which the
# error-rate assertion then (correctly) counts as a product failure that is really
# the missing LB. Disruption tests MUST therefore keep the load's owner alive by
# exporting it in PICK_EXCLUDE before picking a victim (pick_non_leader honors
# PICK_EXCLUDE — see lib/cluster.sh) — measuring the product, not the absent LB.
#
# RE-RESOLVE (cloud, optional 6th arg): when the pinned owner cannot be kept alive
# (scale-down removes it; a kill targets it), pass the slice coords as $6 so the
# loop re-resolves the CURRENT active owner after 3 consecutive non-2xx ticks. With
# no LB the loop finds the new owner via slice_owner_for → _resolve_live_endpoint
# (now cluster-aware) and re-points APP_ENDPOINT, instead of failing every tick for
# the rest of the window. Callers that omit $6 behave exactly as before.
start_load() {
    local rps="$1" duration="$2" method="$3" path="$4" body="${5:-}" reresolve_coords="${6:-}"
    local app_port="${APP_PORT:-8070}"
    local interval
    interval=$(awk "BEGIN {printf \"%.4f\", 1.0/${rps}}" 2>/dev/null || echo "0.1")
    local end_time=$(($(now_epoch) + duration))

    # Clear any result/failure files left by a PRIOR load window of THIS process
    # before starting a new one. Scoped to $$ so concurrent runners (parallel
    # cluster A suites in separate processes) never clobber each other's files, and
    # so a previous window's stale 404s are not summed into this window's totals
    # (the 642-stale-404 over-count came from stop_load globbing /tmp/load_*_*.txt
    # across runs; both ends are now $$-scoped).
    rm -f "/tmp/load_result_$$.txt" "/tmp/load_failures_$$.txt"

    log_info "Starting load: ${rps} rps for ${duration}s — ${method} ${path}"

    (
        local success=0 failure=0 consec_fail=0
        while [ "$(now_epoch)" -lt "$end_time" ]; do
            local status
            if [ "$method" = "GET" ]; then
                status=$(http_status "${APP_ENDPOINT}${path}" -H "X-API-Key: ${API_KEY}")
            else
                status=$(http_status "${APP_ENDPOINT}${path}" \
                    -X "$method" \
                    -H "X-API-Key: ${API_KEY}" \
                    -H "Content-Type: application/json" \
                    -d "$body")
            fi
            if [ "$status" -ge 200 ] && [ "$status" -lt 300 ] 2>/dev/null; then
                success=$((success + 1))
                consec_fail=0
            else
                failure=$((failure + 1))
                # Per-failure forensics: status 000 = transport/connect error
                # (target node down), 4xx/5xx = node up but request rejected.
                echo "$(date -u +%H:%M:%S) ${status}" >> "/tmp/load_failures_$$.txt"
                consec_fail=$((consec_fail + 1))
                if [ -n "$reresolve_coords" ] && [ "$consec_fail" -ge 3 ]; then
                    # The pinned slice-owner died under churn; there is no LB, so find the
                    # CURRENT active owner and re-point. slice_owner_for → api_get →
                    # _resolve_live_endpoint (now cluster-aware) survives replacement.
                    local new_owner new_ip
                    new_owner=$(slice_owner_for "$reresolve_coords" 2>/dev/null || true)
                    if [ -n "$new_owner" ]; then
                        if [ "${ENV_TYPE:-docker}" = "cloud" ]; then
                            new_ip=$(cloud_public_ip "$new_owner" 2>/dev/null || true)
                            [ -n "$new_ip" ] && APP_ENDPOINT="http://${new_ip}:${app_port}"
                        fi
                    fi
                    consec_fail=0
                fi
            fi
            sleep "$interval"
        done
        echo "${success}:${failure}" > "/tmp/load_result_$$.txt"
    ) &
    LOAD_PIDS+=($!)
    log_info "Load PID: $!"
}

# Start background management API load against MGMT_ENTRY_POINT (witness).
# The witness node is stable by fixture contract; client-side failover was removed to
# exercise the product's HttpForwardRequest contract under chaos rather than masking
# forwarding bugs with per-request port-hopping.
# A tick is success if MGMT_ENTRY_POINT responds 2xx, failure otherwise.
start_mgmt_load() {
    local rps="$1" duration="$2" path="$3"
    local interval
    interval=$(awk "BEGIN {printf \"%.4f\", 1.0/${rps}}" 2>/dev/null || echo "0.5")
    local end_time=$(($(now_epoch) + duration))

    log_info "Starting management load: ${rps} rps for ${duration}s — GET ${path} (via ${MGMT_ENTRY_POINT})"

    (
        local success=0 failure=0
        while [ "$(now_epoch)" -lt "$end_time" ]; do
            local status
            status=$(http_status "${MGMT_ENTRY_POINT}${path}" -H "X-API-Key: ${API_KEY}")
            if [ "$status" -ge 200 ] && [ "$status" -lt 300 ] 2>/dev/null; then
                success=$((success + 1))
            else
                failure=$((failure + 1))
            fi
            sleep "$interval"
        done
        echo "${success}:${failure}" > "/tmp/load_result_$$.txt"
    ) &
    LOAD_PIDS+=($!)
}

# Stop all background load and collect results
stop_load() {
    local total_success=0 total_failure=0

    log_info "Stopping load generators (${#LOAD_PIDS[@]} processes)" >&2
    for pid in "${LOAD_PIDS[@]}"; do
        kill "$pid" 2>/dev/null
        wait "$pid" 2>/dev/null
    done

    # Collect results from temp files. Scope to THIS process ($$) — the previous
    # /tmp/load_result_*.txt glob summed result files left by EVERY prior run/process,
    # inflating totals (observed: 642 stale 404s folded into one window's failure
    # count). start_load now also clears these at the start of each window.
    for f in "/tmp/load_result_$$.txt"; do
        if [ -f "$f" ]; then
            local line
            line=$(cat "$f")
            local s=${line%%:*}
            local fail=${line##*:}
            total_success=$((total_success + s))
            total_failure=$((total_failure + fail))
            rm -f "$f"
        fi
    done

    LOAD_PIDS=()
    log_info "Load results: success=${total_success}, failure=${total_failure}" >&2

    # Failure forensics: status-code histogram + time window of the failures.
    # Discriminates transport errors (000) from routed-but-rejected (4xx/5xx)
    # and shows whether failures cluster in a cutover window or span the run.
    # $$-scoped (matches the result-file scoping above) so a prior run's failure
    # log is never replayed into this window's histogram.
    local ff
    for ff in "/tmp/load_failures_$$.txt"; do
        if [ -f "$ff" ]; then
            log_info "Failure status histogram: $(awk '{print $2}' "$ff" | sort | uniq -c | awk '{printf "%sx%s ", $1, $2}')" >&2
            log_info "Failure window: first=$(head -1 "$ff") last=$(tail -1 "$ff")" >&2
            rm -f "$ff"
        fi
    done

    echo "${total_success}:${total_failure}"
}

# Get load error rate (percentage)
load_error_rate() {
    local result="$1"
    local success=${result%%:*}
    local failure=${result##*:}
    local total=$((success + failure))
    if [ "$total" -eq 0 ]; then
        echo "0"
        return
    fi
    awk "BEGIN {printf \"%.2f\", ${failure} * 100.0 / ${total}}"
}

# Assert error rate is below threshold
assert_error_rate_below() {
    local result="$1" threshold="$2" desc="$3"
    local rate
    rate=$(load_error_rate "$result")
    local ok
    ok=$(awk "BEGIN {print (${rate} < ${threshold}) ? \"yes\" : \"no\"}")
    if [ "$ok" = "yes" ]; then
        log_pass "${desc} (error rate: ${rate}%)"
        return 0
    fi
    log_fail "${desc}: error rate ${rate}% exceeds threshold ${threshold}%"
    return 1
}

# ---------------------------------------------------------------------------
# Sustained load with periodic metric snapshots
# ---------------------------------------------------------------------------
start_sustained_load() {
    local rps="$1" duration="$2" method="$3" path="$4" body="${5:-}" log_file="${6:-/tmp/sustained_load.log}"
    local interval
    interval=$(awk "BEGIN {printf \"%.4f\", 1.0/${rps}}" 2>/dev/null || echo "0.1")
    local end_time=$(($(now_epoch) + duration))

    log_info "Starting sustained load: ${rps} rps for ${duration}s — log: ${log_file}"

    (
        local success=0 failure=0 count=0
        while [ "$(now_epoch)" -lt "$end_time" ]; do
            local status start_ms end_ms latency
            start_ms=$(date +%s%3N)
            if [ "$method" = "GET" ]; then
                status=$(http_status "${APP_ENDPOINT}${path}" -H "X-API-Key: ${API_KEY}")
            else
                status=$(http_status "${APP_ENDPOINT}${path}" \
                    -X "$method" \
                    -H "X-API-Key: ${API_KEY}" \
                    -H "Content-Type: application/json" \
                    -d "$body")
            fi
            end_ms=$(date +%s%3N)
            latency=$((end_ms - start_ms))

            if [ "$status" -ge 200 ] && [ "$status" -lt 300 ] 2>/dev/null; then
                success=$((success + 1))
            else
                failure=$((failure + 1))
            fi
            count=$((count + 1))

            # Log every 100 requests
            if [ $((count % 100)) -eq 0 ]; then
                echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) count=${count} success=${success} failure=${failure} latency_ms=${latency}" >> "$log_file"
            fi

            sleep "$interval"
        done
        echo "${success}:${failure}" > "/tmp/load_result_$$.txt"
        echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) FINAL count=${count} success=${success} failure=${failure}" >> "$log_file"
    ) &
    LOAD_PIDS+=($!)
}

# ---------------------------------------------------------------------------
# Burst load — send N requests as fast as possible
# ---------------------------------------------------------------------------
burst_load() {
    local count="$1" method="$2" path="$3" body="${4:-}"
    local success=0 failure=0

    log_info "Burst: ${count} requests — ${method} ${path}"
    for ((i = 0; i < count; i++)); do
        local status
        if [ "$method" = "GET" ]; then
            status=$(http_status "${APP_ENDPOINT}${path}" -H "X-API-Key: ${API_KEY}")
        else
            status=$(http_status "${APP_ENDPOINT}${path}" \
                -X "$method" \
                -H "X-API-Key: ${API_KEY}" \
                -H "Content-Type: application/json" \
                -d "$body")
        fi
        if [ "$status" -ge 200 ] && [ "$status" -lt 300 ] 2>/dev/null; then
            success=$((success + 1))
        else
            failure=$((failure + 1))
        fi
    done

    log_info "Burst results: success=${success}, failure=${failure}"
    echo "${success}:${failure}"
}
