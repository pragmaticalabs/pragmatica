#!/bin/bash
# test-stream-replication.sh — Verify stream replication across nodes
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

STREAM_NAME="${STREAM_NAME:-repl-test-events}"
REPLICATION_FACTOR=2

test_cluster_ready() {
    wait_for_cluster_ready 60
    wait_for_all_tasks_active 60 || log_warn "task groups not fully ACTIVE within 60s"
    log_pass "Cluster ready"
}

test_create_stream() {
    local result
    result=$(stream_create "$STREAM_NAME" 1)
    # #1224: assert against the CATALOG, not the response echo. The old assertion matched the
    # stream name inside the 200 body, which the response contains whether or not the stream was
    # ever registered — it reported PASS on every run while the stream did not exist.
    assert_ne "$(stream_coordinate "$STREAM_NAME" 2>/dev/null)" "" \
              "Stream ${STREAM_NAME} present in catalog after create"
}

test_publish_events_for_replication() {
    local success=0 errfile
    errfile=$(mktemp)
    for i in $(seq 1 10); do
        local payload="{\"key\":\"repl-${i}\",\"data\":\"replicated-payload-${i}\",\"timestamp\":$(now_epoch)}"
        # `2>&1` here discarded the `api ... status=NNN: <body>` diagnostic `_api_call` emits on
        # stderr, leaving a short count as the only evidence of why a publish failed.
        if stream_publish "$STREAM_NAME" "$payload" > /dev/null 2>>"$errfile"; then
            success=$((success + 1))
        fi
    done
    [ "$success" -eq 10 ] || log_warn "Replication publish diagnostics (first 500B): $(head -c 500 "$errfile" 2>/dev/null | tr -d '\n')"
    rm -f "$errfile"
    assert_eq "$success" "10" "All 10 events published for replication test"
}

test_stream_visible_on_governor() {
    local info
    info=$(stream_info "$STREAM_NAME")
    assert_ne "$info" "" "Stream info available on governor node"
}

test_read_events_from_partition() {
    # RC1-blocker fix (audit 2026-05-21 §2.2 #1): the prior implementation
    # accepted `{"events":[]}` as success because `assert_contains "$result" "events"`
    # matches the field name regardless of array contents. The publish→read
    # invariant was unverified — a broken read path that always returned an
    # empty events array would have passed.
    #
    # New behavior: drive the read through the `aether streams read` CLI
    # (commit 04ebd4482) so we exercise the same surface operators use, and
    # count event records server-side. The ReadEventsResponse shape is
    # `{"events":[{"offset":N,"data":"...","timestamp":N}, ...]}` (see
    # aether/node/.../StreamRoutes.java::EventRecord). Every event carries
    # exactly one `"offset"` field, so a count of `"offset"` occurrences inside
    # the events array equals the number of returned events.
    #
    # The CLI takes the CATALOG IDENTITY (`namespace:stream:version`), never a bare name: #1044
    # made a bare name a hard error ("is a bare stream name, which is ambiguous: it names no
    # namespace"), because the bare form used to default to `system:`, which holds no app stream.
    # `stream_identity` resolves it from the live catalog, so this cannot drift again.
    local result event_count identity
    identity=$(stream_identity "$STREAM_NAME") || {
        log_fail "stream_identity ${STREAM_NAME} failed — stream absent from the catalog"
        return 1
    }
    result=$(aether_failover streams read "$identity" 0 --limit 50 --format json) || {
        log_fail "aether streams read ${identity} 0 failed (exit non-zero)"
        return 1
    }
    if [ -z "$result" ]; then
        log_fail "aether streams read ${identity} 0 returned empty body"
        return 1
    fi
    event_count=$(printf '%s' "$result" | grep -oE '"offset"[[:space:]]*:' | wc -l | tr -d ' ')
    event_count="${event_count:-0}"
    # Publish phase issued 10 events; require at least 1 to land (strict-N
    # would race replication ack timing, but >=1 is the publish→read invariant
    # we set out to verify).
    assert_ge "$event_count" "1" "streams read returned >=1 event after publish (got ${event_count}; first 300 chars: ${result:0:300})"
}

# Attempt to read stream data via a non-leader node.
# This exercises the replication path if governor-push replication is wired.
# If replication transport is not yet connected in AetherNode, we verify
# basic stream accessibility and skip the cross-node assertion gracefully.
test_read_from_non_governor_node() {
    local leader
    leader=$(cluster_leader)
    if [ -z "$leader" ]; then
        skip_test "Read from non-governor" "No leader detected"
        return 0
    fi

    local alt_node
    alt_node=$(pick_non_leader "$leader" 1)
    if [ -z "$alt_node" ]; then
        skip_test "Read from non-governor" "Could not pick alternate node"
        return 0
    fi

    # Compose the alt node's management endpoint. Cloud and docker-remote layouts
    # differ: cloud has one VM per node with mgmt on a fixed port (CLOUD_MGMT_PORT,
    # default 8080), so the alt host is the node's own public IP. Docker-remote
    # collocates all 5 nodes on TARGET_HOST and host-maps a per-node port range
    # (MGMT_PORT + index) — the standard docker-remote node→port derivation used
    # throughout lib/cluster.sh.
    local alt_endpoint
    if [ "${ENV_TYPE:-}" = "cloud" ]; then
        local alt_ip
        if ! alt_ip=$(cloud_public_ip "$alt_node"); then
            skip_test "Read from non-governor" "cloud_public_ip lookup failed for ${alt_node}"
            return 0
        fi
        alt_endpoint="http://${alt_ip}:${CLOUD_MGMT_PORT:-8080}"
    else
        local node_index
        node_index=$(echo "$alt_node" | grep -o '[0-9]*$')
        local alt_port=$((MGMT_PORT + node_index - 1))
        alt_endpoint="http://${TARGET_HOST}:${alt_port}"
    fi

    # The metadata route is `/streams/{namespace}/{stream}/{version}` since the 2026-09-02 catalog
    # migration (7a523c9e3). The flat two-segment `/streams/{name}` used here did not 404 — it
    # MISROUTED into the same RouteMatcher bucket and answered 500, which `curl -sf` turned into an
    # empty body, and the `assert_ne` below then reported as "metadata absent from the non-governor",
    # i.e. a replication gap that was never real.
    local result coord status
    coord=$(stream_coordinate "$STREAM_NAME") || {
        log_fail "stream_coordinate ${STREAM_NAME} failed — stream absent from the catalog"
        return 1
    }
    # `_api_call`-style error capture so a connection-refused at the alt endpoint
    # surfaces as a warn rather than silently collapsing to empty body (which the
    # `assert_ne` below would conflate with "stream metadata absent"). The old `-sf`
    # discarded the error BODY, which is the diagnosis; take the status from a `-w`
    # trailer instead — one request, and no dependency on curl's `--fail-with-body`.
    local raw
    raw=$(curl -sk -H "X-API-Key: ${API_KEY}" --connect-timeout 5 \
               -w "\n__API_HTTP_STATUS:%{http_code}__" \
               "${alt_endpoint}/api/v1/streams/${coord}" 2>&1) || true
    status=$(printf '%s' "$raw" | grep -oE '__API_HTTP_STATUS:[0-9]+__' | sed 's/__API_HTTP_STATUS://;s/__//')
    result=$(printf '%s' "$raw" | sed '$d')
    if ! { [ -n "$status" ] && [ "$status" -ge 200 ] && [ "$status" -lt 300 ] 2>/dev/null; }; then
        log_warn "Read from non-governor: status=${status:-000} from ${alt_endpoint}/api/v1/streams/${coord} :: body=$(printf '%s' "$result" | head -c 300 | tr -d '\n')"
        result=""
    fi

    # Empty IS the failure mode — replication is the feature under test. If the
    # non-governor cannot serve stream metadata we have a real replication gap,
    # not a "not yet wired" excuse to log_warn and pass.
    assert_ne "$result" "" "Stream metadata reachable from non-governor ${alt_node}"
    assert_contains "$result" "$STREAM_NAME" "Stream metadata accessible from non-governor node"
}

test_stream_in_list_after_replication() {
    local streams
    streams=$(stream_list)
    assert_contains "$streams" "$STREAM_NAME" "Replicated stream visible in list"
}

run_test "Cluster ready" test_cluster_ready
run_test "Create stream with replication" test_create_stream
run_test "Publish 10 events" test_publish_events_for_replication
run_test "Stream visible on governor" test_stream_visible_on_governor
run_test "Read events from partition" test_read_events_from_partition
run_test "Read from non-governor node" test_read_from_non_governor_node
run_test "Stream in list after replication" test_stream_in_list_after_replication
print_summary
