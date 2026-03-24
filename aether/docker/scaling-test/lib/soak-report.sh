#!/bin/bash
# Soak Test Report Generator — queries Prometheus and produces a markdown report.
# Usage: ./soak-report.sh [output-file]
# If no output file specified, writes to stdout.
set -euo pipefail

PROM_URL="${PROM_URL:-http://localhost:9090}"
MGMT_URL="${MGMT_URL:-http://localhost:8080}"
OUTPUT="${1:-/dev/stdout}"

# ── Helpers ────────────────────────────────────────────────────────────────────

prom_query() {
    local query="$1"
    local raw
    raw=$(curl -sf "${PROM_URL}/api/v1/query" --data-urlencode "query=${query}" 2>/dev/null || echo '{}')
    echo "$raw" | jq -r '.data.result[0].value[1] // "0"' 2>/dev/null || echo "0"
}

prom_query_all() {
    local query="$1"
    local raw
    raw=$(curl -sf "${PROM_URL}/api/v1/query" --data-urlencode "query=${query}" 2>/dev/null || echo '{}')
    echo "$raw" | jq -r '[.data.result[].value[1] // "0"] | map(tonumber) | add // 0' 2>/dev/null || echo "0"
}

bytes_to_mb() {
    echo "scale=0; $1 / 1048576" | bc 2>/dev/null || echo "0"
}

seconds_to_ms() {
    echo "scale=1; $1 * 1000" | bc 2>/dev/null || echo "0"
}

pct_change() {
    local old="$1" new="$2"
    if [ "$(echo "$old == 0" | bc 2>/dev/null)" = "1" ]; then
        echo "0"
        return
    fi
    echo "scale=1; ($new - $old) * 100 / $old" | bc 2>/dev/null || echo "0"
}

trend_label() {
    local change="$1"
    if [ "$(echo "$change < -5" | bc 2>/dev/null)" = "1" ]; then
        echo "improving"
    elif [ "$(echo "$change > 10" | bc 2>/dev/null)" = "1" ]; then
        echo "degrading"
    else
        echo "stable"
    fi
}

# ── Gather All Metrics ────────────────────────────────────────────────────────

# Heap
heap_hour1=$(prom_query_all 'max_over_time(jvm_memory_used_bytes{area="heap"}[30m] offset 3h)')
heap_hour2=$(prom_query_all 'max_over_time(jvm_memory_used_bytes{area="heap"}[30m] offset 2h)')
heap_hour3=$(prom_query_all 'max_over_time(jvm_memory_used_bytes{area="heap"}[30m] offset 1h)')
heap_hour4=$(prom_query_all 'max_over_time(jvm_memory_used_bytes{area="heap"}[30m])')
heap_hour1_mb=$(bytes_to_mb "$heap_hour1")
heap_hour2_mb=$(bytes_to_mb "$heap_hour2")
heap_hour3_mb=$(bytes_to_mb "$heap_hour3")
heap_hour4_mb=$(bytes_to_mb "$heap_hour4")
heap_change=$(pct_change "$heap_hour1" "$heap_hour4")

# GC
gc_max_s=$(prom_query 'max(max_over_time(jvm_gc_pause_seconds_max[1h]))')
gc_max_ms=$(seconds_to_ms "$gc_max_s")

# P99 latency per hour
p99_h1_s=$(prom_query 'histogram_quantile(0.99, sum(rate(aether_http_request_seconds_bucket[30m] offset 3h)) by (le))')
p99_h2_s=$(prom_query 'histogram_quantile(0.99, sum(rate(aether_http_request_seconds_bucket[30m] offset 2h)) by (le))')
p99_h3_s=$(prom_query 'histogram_quantile(0.99, sum(rate(aether_http_request_seconds_bucket[30m] offset 1h)) by (le))')
p99_h4_s=$(prom_query 'histogram_quantile(0.99, sum(rate(aether_http_request_seconds_bucket[30m])) by (le))')
p99_h1_ms=$(seconds_to_ms "$p99_h1_s")
p99_h2_ms=$(seconds_to_ms "$p99_h2_s")
p99_h3_ms=$(seconds_to_ms "$p99_h3_s")
p99_h4_ms=$(seconds_to_ms "$p99_h4_s")
p99_change=$(pct_change "$p99_h1_s" "$p99_h4_s")

# Error rate per hour
err_h1=$(prom_query 'sum(rate(aether_http_requests_total{status=~"5.."}[1h] offset 3h)) or vector(0)')
req_h1=$(prom_query 'sum(rate(aether_http_requests_total[1h] offset 3h)) or vector(0)')
err_h2=$(prom_query 'sum(rate(aether_http_requests_total{status=~"5.."}[1h] offset 2h)) or vector(0)')
req_h2=$(prom_query 'sum(rate(aether_http_requests_total[1h] offset 2h)) or vector(0)')
err_h3=$(prom_query 'sum(rate(aether_http_requests_total{status=~"5.."}[1h] offset 1h)) or vector(0)')
req_h3=$(prom_query 'sum(rate(aether_http_requests_total[1h] offset 1h)) or vector(0)')
err_h4=$(prom_query 'sum(rate(aether_http_requests_total{status=~"5.."}[1h])) or vector(0)')
req_h4=$(prom_query 'sum(rate(aether_http_requests_total[1h])) or vector(0)')

compute_error_pct() {
    local err="$1" total="$2"
    if [ "$(echo "$total == 0" | bc 2>/dev/null)" = "1" ]; then
        echo "0"
    else
        echo "scale=2; $err * 100 / $total" | bc 2>/dev/null || echo "0"
    fi
}

err_pct_h1=$(compute_error_pct "$err_h1" "$req_h1")
err_pct_h2=$(compute_error_pct "$err_h2" "$req_h2")
err_pct_h3=$(compute_error_pct "$err_h3" "$req_h3")
err_pct_h4=$(compute_error_pct "$err_h4" "$req_h4")

# RPS per hour
rps_h1=$(prom_query 'sum(rate(aether_http_requests_total[1h] offset 3h)) or vector(0)')
rps_h2=$(prom_query 'sum(rate(aether_http_requests_total[1h] offset 2h)) or vector(0)')
rps_h3=$(prom_query 'sum(rate(aether_http_requests_total[1h] offset 1h)) or vector(0)')
rps_h4=$(prom_query 'sum(rate(aether_http_requests_total[1h])) or vector(0)')

# Node count
node_count=$(prom_query 'max(aether_cluster_nodes) or count(up{job=~"aether.*"} == 1)')

# Targets up
targets_up=$(prom_query 'count(up{job=~"aether.*"} == 1)')

# FAULTY check via management API
faulty_count=0
status_json=$(curl -sf "${MGMT_URL}/api/status" 2>/dev/null || echo '{}')
faulty_count=$(echo "$status_json" | jq '[.cluster.nodes[]? | select(.state? == "FAULTY")] | length' 2>/dev/null || echo "0")

# Trends
heap_trend=$(trend_label "$heap_change")
latency_trend=$(trend_label "$p99_change")

# ── Verdict Table ─────────────────────────────────────────────────────────────

heap_verdict="PASS"
[ "$(echo "$heap_hour4 > $heap_hour1 * 1.1" | bc 2>/dev/null)" = "1" ] && heap_verdict="FAIL"

gc_verdict="PASS"
[ "$(echo "$gc_max_ms >= 500" | bc 2>/dev/null)" = "1" ] && gc_verdict="FAIL"

p99_verdict="PASS"
[ "$(echo "$p99_h4_s > $p99_h1_s * 1.1" | bc 2>/dev/null)" = "1" ] && p99_verdict="FAIL"

overall_err=$(compute_error_pct "$(prom_query 'sum(rate(aether_http_requests_total{status=~"5.."}[4h])) or vector(0)')" \
                                "$(prom_query 'sum(rate(aether_http_requests_total[4h])) or vector(0)')")
err_verdict="PASS"
[ "$(echo "$overall_err >= 1" | bc 2>/dev/null)" = "1" ] && err_verdict="FAIL"

swim_verdict="PASS"
[ "$faulty_count" != "0" ] && swim_verdict="FAIL"

node_verdict="PASS"
[ "$(echo "$node_count < 12" | bc 2>/dev/null)" = "1" ] && node_verdict="FAIL"

fail_count=0
for v in "$heap_verdict" "$gc_verdict" "$p99_verdict" "$err_verdict" "$swim_verdict" "$node_verdict"; do
    [ "$v" = "FAIL" ] && fail_count=$((fail_count + 1))
done

if [ "$fail_count" -eq 0 ]; then
    overall="PASS"
else
    overall="FAIL (${fail_count} criteria failed)"
fi

# Quorum check during hour 3 (rolling restart)
# If node count stayed >= 3 (majority of 5 cores), quorum was maintained
quorum_maintained="yes"
min_nodes_h3=$(prom_query 'min_over_time(aether_cluster_nodes[1h] offset 1h) or vector(0)')
[ "$(echo "$min_nodes_h3 < 3" | bc 2>/dev/null)" = "1" ] && quorum_maintained="no (min: ${min_nodes_h3})"

# ── Generate Report ───────────────────────────────────────────────────────────

cat > "$OUTPUT" <<REPORT
# Soak Test Report

**Date:** $(date '+%Y-%m-%d %H:%M:%S')
**Duration:** 4 hours
**Load:** 100 VUs sustained
**Cluster:** 5 core + 7 worker nodes

## Summary

| Criterion                    | Result | Details |
|------------------------------|--------|---------|
| Heap growth (< 10%)          | ${heap_verdict}   | Hour 1: ${heap_hour1_mb}MB, Hour 4: ${heap_hour4_mb}MB (${heap_change}%) |
| GC max pause (< 500ms)       | ${gc_verdict}   | Max: ${gc_max_ms}ms |
| P99 latency drift (< 10%)    | ${p99_verdict}   | Hour 1: ${p99_h1_ms}ms, Hour 4: ${p99_h4_ms}ms (${p99_change}%) |
| Error rate (< 1%)            | ${err_verdict}   | ${overall_err}% |
| SWIM FAULTY (0 after warmup) | ${swim_verdict}   | ${faulty_count} events |
| Node count (12 in hour 4)    | ${node_verdict}   | ${node_count}/12 |

**Overall: ${overall}**

## Phase Analysis

### Hour 1: Baseline
- Avg RPS: ${rps_h1}
- P99 latency: ${p99_h1_ms}ms
- Error rate: ${err_pct_h1}%
- Heap max: ${heap_hour1_mb}MB

### Hour 2: Worker Kill Chaos
- Worker-8 killed at T+60m
- Error spike: ${err_pct_h2}% error rate
- P99 latency: ${p99_h2_ms}ms
- Heap during chaos: ${heap_hour2_mb}MB
- Avg RPS: ${rps_h2}

### Hour 3: Rolling Restart
- Node-2 restarted at T+120m
- Node-3 restarted at T+125m
- Quorum maintained: ${quorum_maintained}
- Error spike: ${err_pct_h3}% error rate
- P99 latency: ${p99_h3_ms}ms
- Heap during restart: ${heap_hour3_mb}MB
- Avg RPS: ${rps_h3}

### Hour 4: Recovery
- All metrics targets up: ${targets_up}/12
- Heap trend: ${heap_trend}
- Latency trend: ${latency_trend}
- Error rate: ${err_pct_h4}%
- P99 latency: ${p99_h4_ms}ms
- Heap: ${heap_hour4_mb}MB
- Avg RPS: ${rps_h4}

## Verdict

**${overall}**
REPORT
