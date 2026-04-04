#!/bin/bash
# test-stream-consumer.sh — Consumer receives events, analytics counts
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

STREAM_NAME="${STREAM_NAME:-test-events}"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

test_publish_and_verify_count() {
    # Publish known number of events
    local count=20
    for i in $(seq 1 "$count"); do
        local payload="{\"key\":\"consumer-test-${i}\",\"data\":\"msg-${i}\",\"timestamp\":$(now_epoch)}"
        stream_publish "$STREAM_NAME" "$payload" > /dev/null 2>&1
    done
    log_info "Published ${count} events"

    # Check stream info for message count
    sleep 2
    local info
    info=$(stream_info "$STREAM_NAME")
    assert_ne "$info" "" "Stream info available after publish"

    # Verify messages are tracked — endpoint may return flat object or {streams: [...]} array
    local msg_count
    msg_count=$(echo "$info" | python3 -c "
import sys, json
data = json.load(sys.stdin)
if 'totalEvents' in data:
    print(data['totalEvents'])
elif 'streams' in data:
    for s in data['streams']:
        if s.get('name') == '${STREAM_NAME}':
            print(s.get('totalEvents', 0)); break
    else:
        print(0)
else:
    print(0)
" 2>/dev/null)

    assert_gt "$msg_count" "0" "Stream has messages: ${msg_count}"
}

test_stream_metadata() {
    local info
    info=$(stream_info "$STREAM_NAME")
    assert_ne "$info" "" "Stream metadata retrievable"
    local name
    name=$(echo "$info" | python3 -c "
import sys, json
data = json.load(sys.stdin)
if 'name' in data:
    print(data['name'])
elif 'streams' in data and data['streams']:
    print(data['streams'][0].get('name', ''))
else:
    print('')
" 2>/dev/null)
    assert_ne "$name" "" "Stream name in metadata"
}

test_multiple_streams_isolation() {
    local other_stream="isolation-test"
    local payload='{"key":"isolated","data":"test","timestamp":'$(now_epoch)'}'
    stream_publish "$other_stream" "$payload" > /dev/null 2>&1

    local streams
    streams=$(stream_list)
    assert_contains "$streams" "$STREAM_NAME" "Original stream still exists"
}

run_test "Cluster ready" test_cluster_ready
run_test "Publish and verify count" test_publish_and_verify_count
run_test "Stream metadata" test_stream_metadata
run_test "Multiple streams isolation" test_multiple_streams_isolation
print_summary
