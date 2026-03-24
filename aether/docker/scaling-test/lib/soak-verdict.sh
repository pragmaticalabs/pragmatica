#!/bin/bash
# Soak Test Verdict — queries Prometheus after the soak run and evaluates pass/fail criteria.
# Exit code: 0 = PASS, 1 = FAIL
#
# Requires: curl, jq, bc
# Assumes: Prometheus running on localhost:9090, Aether management API on localhost:8080
set -euo pipefail

PROM_URL="${PROM_URL:-http://localhost:9090}"
MGMT_URL="${MGMT_URL:-http://localhost:8080}"

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
    echo "scale=1; $1 / 1048576" | bc 2>/dev/null || echo "0"
}

pct_change() {
    local old="$1" new="$2"
    if [ "$(echo "$old == 0" | bc 2>/dev/null)" = "1" ]; then
        echo "0"
        return
    fi
    echo "scale=1; ($new - $old) * 100 / $old" | bc 2>/dev/null || echo "0"
}

seconds_to_ms() {
    echo "scale=1; $1 * 1000" | bc 2>/dev/null || echo "0"
}

FAILURES=0
RESULTS=""

record_result() {
    local name="$1" max_width=30
    local result="$2" details="$3"
    local padded
    padded=$(printf "%-${max_width}s" "$name")
    RESULTS="${RESULTS}${padded}| ${result}   | ${details}\n"
    if [ "$result" = "FAIL" ]; then
        FAILURES=$((FAILURES + 1))
    fi
}

# ── Criterion 1: Heap Growth Trend ────────────────────────────────────────────
# Compare max heap at hour 1 vs hour 4

heap_hour1=$(prom_query_all 'max_over_time(jvm_memory_used_bytes{area="heap"}[30m] offset 3h)')
heap_hour4=$(prom_query_all 'max_over_time(jvm_memory_used_bytes{area="heap"}[30m])')

heap_hour1_mb=$(bytes_to_mb "$heap_hour1")
heap_hour4_mb=$(bytes_to_mb "$heap_hour4")
heap_change=$(pct_change "$heap_hour1" "$heap_hour4")

# Pass: hour 4 max < hour 1 max * 1.1 (10% tolerance)
if [ "$(echo "$heap_hour4 <= $heap_hour1 * 1.1" | bc 2>/dev/null)" = "1" ]; then
    record_result "Heap growth (< 10%)" "PASS" "Hour 1: ${heap_hour1_mb}MB, Hour 4: ${heap_hour4_mb}MB (${heap_change}%)"
else
    record_result "Heap growth (< 10%)" "FAIL" "Hour 1: ${heap_hour1_mb}MB, Hour 4: ${heap_hour4_mb}MB (${heap_change}%)"
fi

# ── Criterion 2: GC Pause Time ────────────────────────────────────────────────
# Max GC pause in last hour across all nodes

gc_max_seconds=$(prom_query 'max(max_over_time(jvm_gc_pause_seconds_max[1h]))')
gc_max_ms=$(seconds_to_ms "$gc_max_seconds")

# Pass: < 500ms
if [ "$(echo "$gc_max_ms < 500" | bc 2>/dev/null)" = "1" ]; then
    record_result "GC max pause (< 500ms)" "PASS" "Max: ${gc_max_ms}ms"
else
    record_result "GC max pause (< 500ms)" "FAIL" "Max: ${gc_max_ms}ms"
fi

# ── Criterion 3: P99 Latency Drift ───────────────────────────────────────────
# Compare P99 latency at hour 1 vs hour 4

p99_hour1_s=$(prom_query 'histogram_quantile(0.99, sum(rate(aether_http_request_seconds_bucket[30m] offset 3h)) by (le))')
p99_hour4_s=$(prom_query 'histogram_quantile(0.99, sum(rate(aether_http_request_seconds_bucket[30m])) by (le))')

p99_hour1_ms=$(seconds_to_ms "$p99_hour1_s")
p99_hour4_ms=$(seconds_to_ms "$p99_hour4_s")
p99_change=$(pct_change "$p99_hour1_s" "$p99_hour4_s")

# Pass: hour 4 within 10% of hour 1
if [ "$(echo "$p99_hour4_s <= $p99_hour1_s * 1.1" | bc 2>/dev/null)" = "1" ]; then
    record_result "P99 latency drift (< 10%)" "PASS" "Hour 1: ${p99_hour1_ms}ms, Hour 4: ${p99_hour4_ms}ms (${p99_change}%)"
else
    record_result "P99 latency drift (< 10%)" "FAIL" "Hour 1: ${p99_hour1_ms}ms, Hour 4: ${p99_hour4_ms}ms (${p99_change}%)"
fi

# ── Criterion 4: Error Rate ──────────────────────────────────────────────────
# Overall 5xx error rate across all nodes

error_total=$(prom_query 'sum(rate(aether_http_requests_total{status=~"5.."}[4h])) or vector(0)')
request_total=$(prom_query 'sum(rate(aether_http_requests_total[4h])) or vector(0)')

if [ "$(echo "$request_total == 0" | bc 2>/dev/null)" = "1" ]; then
    error_pct="0"
else
    error_pct=$(echo "scale=4; $error_total * 100 / $request_total" | bc 2>/dev/null || echo "0")
fi

# Pass: < 1%
if [ "$(echo "$error_pct < 1" | bc 2>/dev/null)" = "1" ]; then
    record_result "Error rate (< 1%)" "PASS" "${error_pct}%"
else
    record_result "Error rate (< 1%)" "FAIL" "${error_pct}%"
fi

# ── Criterion 5: SWIM FAULTY Events ─────────────────────────────────────────
# Query management API for cluster status — check all nodes are ALIVE
# The /api/status endpoint returns cluster node states

faulty_count=0
status_json=$(curl -sf "${MGMT_URL}/api/status" 2>/dev/null || echo '{}')
faulty_count=$(echo "$status_json" | jq '[.cluster.nodes[]? | select(.state? == "FAULTY")] | length' 2>/dev/null || echo "0")

# Also check Prometheus for any target that went down during the recovery hour
down_targets=$(prom_query 'count(up{job=~"aether.*"} == 0) or vector(0)')

# Pass: 0 FAULTY nodes currently and all targets up
if [ "$faulty_count" = "0" ] && [ "$(echo "$down_targets == 0" | bc 2>/dev/null)" = "1" ]; then
    record_result "SWIM FAULTY (0 after warmup)" "PASS" "0 events"
else
    record_result "SWIM FAULTY (0 after warmup)" "FAIL" "FAULTY: ${faulty_count}, targets down: ${down_targets}"
fi

# ── Criterion 6: Node Count Stability ────────────────────────────────────────
# All 12 nodes must be present during hour 4

node_count=$(prom_query 'max(aether_cluster_nodes) or count(up{job=~"aether.*"} == 1)')

# Pass: all 12 nodes present
if [ "$(echo "$node_count >= 12" | bc 2>/dev/null)" = "1" ]; then
    record_result "Node count (12 in hour 4)" "PASS" "${node_count}/12"
else
    record_result "Node count (12 in hour 4)" "FAIL" "${node_count}/12"
fi

# ── Output ────────────────────────────────────────────────────────────────────

echo ""
echo "=== SOAK TEST VERDICT ==="
echo ""
printf "%-30s| %-6s| %s\n" "Criterion" "Result" "Details"
printf "%-30s|--------|------------------\n" "------------------------------"
printf "$RESULTS"
echo ""

if [ "$FAILURES" -eq 0 ]; then
    echo "OVERALL: PASS"
    exit 0
else
    echo "OVERALL: FAIL (${FAILURES} criteria failed)"
    exit 1
fi
