#!/bin/bash
# load.sh — Load generation helpers for Aether integration tests (curl-based)

LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${LIB_DIR}/common.sh"

# ---------------------------------------------------------------------------
# Background load generation
# ---------------------------------------------------------------------------
LOAD_PIDS=()

# Start background HTTP load against the app endpoint
# Usage: start_load <rps> <duration_seconds> <method> <path> [body]
start_load() {
    local rps="$1" duration="$2" method="$3" path="$4" body="${5:-}"
    local interval
    interval=$(python3 -c "print(1.0 / ${rps})" 2>/dev/null || echo "0.1")
    local end_time=$(($(now_epoch) + duration))

    log_info "Starting load: ${rps} rps for ${duration}s — ${method} ${path}"

    (
        local success=0 failure=0
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
            if [ "$status" -ge 200 ] && [ "$status" -lt 400 ] 2>/dev/null; then
                success=$((success + 1))
            else
                failure=$((failure + 1))
            fi
            sleep "$interval"
        done
        echo "${success}:${failure}" > "/tmp/load_result_$$.txt"
    ) &
    LOAD_PIDS+=($!)
    log_info "Load PID: ${LOAD_PIDS[-1]}"
}

# Start background management API load
start_mgmt_load() {
    local rps="$1" duration="$2" path="$3"
    local interval
    interval=$(python3 -c "print(1.0 / ${rps})" 2>/dev/null || echo "0.5")
    local end_time=$(($(now_epoch) + duration))

    log_info "Starting management load: ${rps} rps for ${duration}s — GET ${path}"

    (
        local success=0 failure=0
        while [ "$(now_epoch)" -lt "$end_time" ]; do
            local status
            status=$(http_status "${CLUSTER_ENDPOINT}${path}" -H "X-API-Key: ${API_KEY}")
            if [ "$status" -ge 200 ] && [ "$status" -lt 400 ] 2>/dev/null; then
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

    log_info "Stopping load generators (${#LOAD_PIDS[@]} processes)"
    for pid in "${LOAD_PIDS[@]}"; do
        kill "$pid" 2>/dev/null
        wait "$pid" 2>/dev/null
    done

    # Collect results from temp files
    for f in /tmp/load_result_*.txt; do
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
    log_info "Load results: success=${total_success}, failure=${total_failure}"
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
    python3 -c "print(round(${failure} * 100.0 / ${total}, 2))"
}

# Assert error rate is below threshold
assert_error_rate_below() {
    local result="$1" threshold="$2" desc="$3"
    local rate
    rate=$(load_error_rate "$result")
    local ok
    ok=$(python3 -c "print('yes' if ${rate} < ${threshold} else 'no')")
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
    interval=$(python3 -c "print(1.0 / ${rps})" 2>/dev/null || echo "0.1")
    local end_time=$(($(now_epoch) + duration))

    log_info "Starting sustained load: ${rps} rps for ${duration}s — log: ${log_file}"

    (
        local success=0 failure=0 count=0
        while [ "$(now_epoch)" -lt "$end_time" ]; do
            local status start_ms end_ms latency
            start_ms=$(python3 -c "import time; print(int(time.time()*1000))")
            if [ "$method" = "GET" ]; then
                status=$(http_status "${APP_ENDPOINT}${path}" -H "X-API-Key: ${API_KEY}")
            else
                status=$(http_status "${APP_ENDPOINT}${path}" \
                    -X "$method" \
                    -H "X-API-Key: ${API_KEY}" \
                    -H "Content-Type: application/json" \
                    -d "$body")
            fi
            end_ms=$(python3 -c "import time; print(int(time.time()*1000))")
            latency=$((end_ms - start_ms))

            if [ "$status" -ge 200 ] && [ "$status" -lt 400 ] 2>/dev/null; then
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
        if [ "$status" -ge 200 ] && [ "$status" -lt 400 ] 2>/dev/null; then
            success=$((success + 1))
        else
            failure=$((failure + 1))
        fi
    done

    log_info "Burst results: success=${success}, failure=${failure}"
    echo "${success}:${failure}"
}
