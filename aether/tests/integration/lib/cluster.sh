#!/bin/bash
# cluster.sh — Cluster lifecycle operations for Aether integration tests

LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${LIB_DIR}/common.sh"

# ---------------------------------------------------------------------------
# Cluster queries (CLI-based)
# ---------------------------------------------------------------------------
cluster_node_count() {
    # Use health endpoint (QUIC peer count) — reliable immediately.
    # /api/status nodeCount comes from metrics aggregation which lags on startup.
    api_get "/api/health" | python3 -c "import sys,json; print(json.load(sys.stdin).get('nodeCount',0))" 2>/dev/null
}

cluster_leader() {
    aether_field status cluster.leaderId
}

cluster_status() {
    aether_json status
}

cluster_health() {
    aether_json health
}

cluster_events() {
    aether_json events
}

cluster_node_list() {
    aether_json status | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    nodes = data.get('cluster', {}).get('nodes', [])
    json.dump(nodes, sys.stdout)
except:
    print('[]')
" 2>/dev/null
}

# Pick a non-leader node ID from the known set (integration-test-1..5)
pick_non_leader() {
    local leader="$1"
    local count="${2:-1}"
    local found=0
    for i in 1 2 3 4 5; do
        local candidate="integration-test-$i"
        if [ "$candidate" != "$leader" ]; then
            echo "$candidate"
            found=$((found + 1))
            if [ "$found" -ge "$count" ]; then
                return 0
            fi
        fi
    done
}

cluster_slices() {
    aether_json slices
}

cluster_config() {
    aether_json config
}

# ---------------------------------------------------------------------------
# Health checks
# ---------------------------------------------------------------------------
is_cluster_healthy() {
    local status
    status=$(aether_field health status)
    [ "$status" = "UP" ] || [ "$status" = "healthy" ]
}

assert_cluster_healthy() {
    local desc="$1"
    local health
    health=$(aether_field health status)
    assert_eq "$health" "healthy" "$desc"
}

is_cluster_ready() {
    local count
    count=$(cluster_node_count)
    [ -n "$count" ] && [ "$count" -ge 3 ] 2>/dev/null
}

# ---------------------------------------------------------------------------
# Wait helpers
# ---------------------------------------------------------------------------
wait_for_cluster() {
    wait_for "cluster healthy" "is_cluster_ready" "${1:-120}"
}

wait_for_node_count() {
    local expected="$1" timeout="${2:-120}"
    wait_for "${expected} nodes" "[ \$(cluster_node_count) -eq ${expected} ]" "$timeout"
}

wait_for_leader() {
    wait_for "leader elected" "[ -n \"\$(cluster_leader)\" ]" "${1:-60}"
}

wait_for_slices_active() {
    local min_instances="${1:-1}" timeout="${2:-120}"
    wait_for "slices active (>= ${min_instances} instances)" \
        "[ \$(slices_total_instances) -ge ${min_instances} ]" "$timeout"
}

# ---------------------------------------------------------------------------
# Slice operations
# ---------------------------------------------------------------------------
slices_total_instances() {
    local slices
    slices=$(cluster_slices)
    echo "$slices" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if isinstance(data, dict) and 'slices' in data:
        sl = data['slices']
        if sl and isinstance(sl[0], dict):
            # Cluster-wide format: count running instances (LOADED or ACTIVE)
            print(sum(len([i for i in s.get('instances', []) if i.get('state') in ('LOADED', 'ACTIVE')]) for s in sl))
        else:
            # Flat string list (per-node format)
            print(len(sl))
    elif isinstance(data, list):
        print(len(data))
    else:
        print(0)
except:
    print(0)
" 2>/dev/null
}

push_blueprint() {
    local coords="$1"
    log_info "Pushing blueprint artifacts: ${coords}" >&2
    aether_failover artifact push "$coords" 2>/dev/null
}

deploy_blueprint() {
    local artifact="$1"
    log_info "Deploying blueprint: ${artifact}" >&2
    aether_failover blueprint deploy "$artifact" 2>/dev/null \
        || api_post "/api/blueprint/deploy" "{\"artifact\":\"${artifact}\"}"
}

deploy_blueprint_file() {
    local filepath="$1"
    log_info "Deploying blueprint file: ${filepath}" >&2
    local content
    content=$(cat "$filepath")
    curl -sf -X POST -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/toml" \
        -d "$content" "${CLUSTER_ENDPOINT}/api/blueprint"
}

list_blueprints() {
    aether_json blueprint list 2>/dev/null || api_get "/api/blueprints"
}

# ---------------------------------------------------------------------------
# Node operations
# ---------------------------------------------------------------------------
kill_node() {
    local node_id="$1"
    log_info "Killing node: ${node_id}"
    remote_exec "docker kill aether-${node_id}" 2>/dev/null
}

start_node() {
    local node_id="$1"
    log_info "Starting node: ${node_id}"
    remote_exec "docker start aether-${node_id}" 2>/dev/null
}

# Restart all containers for clean cluster formation
restart_all_nodes() {
    log_info "Restarting all cluster containers..."
    remote_exec "docker ps -a --filter 'name=aether-integration-test' -q | xargs -r docker restart" 2>/dev/null
}

drain_node() {
    local node_id="$1"
    log_info "Draining node: ${node_id}"
    api_post "/api/node/drain" "{\"nodeId\":\"${node_id}\"}"
}

activate_node() {
    local node_id="$1"
    log_info "Activating node: ${node_id}"
    api_post "/api/node/activate" "{\"nodeId\":\"${node_id}\"}"
}

shutdown_node() {
    local node_id="$1"
    log_info "Shutting down node: ${node_id}"
    api_post "/api/node/shutdown" "{\"nodeId\":\"${node_id}\"}"
}

get_node_lifecycle() {
    api_get "/api/nodes/lifecycle"
}

# ---------------------------------------------------------------------------
# Scaling
# ---------------------------------------------------------------------------
scale_cluster() {
    local target="$1"
    log_info "Scaling cluster to ${target} nodes" >&2
    # Get current cluster config version for optimistic concurrency
    local version
    version=$(api_get "/api/cluster/config" | python3 -c "import sys,json; print(json.load(sys.stdin).get('version',0))" 2>/dev/null || echo "0")
    api_post "/api/cluster/scale" "{\"coreCount\":${target},\"expectedVersion\":${version}}"
}

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
config_apply() {
    local body="$1"
    log_info "Applying config"
    api_post "/api/config" "$body"
}

config_export() {
    aether_json config
}

config_get_key() {
    local key="$1"
    api_get "/api/config/${key}"
}

# ---------------------------------------------------------------------------
# Streams
# ---------------------------------------------------------------------------
stream_list() {
    aether_json streams 2>/dev/null || api_get "/api/streams"
}

stream_info() {
    local name="$1"
    api_get "/api/streams/${name}"
}

stream_publish() {
    local name="$1" body="$2"
    api_post "/api/streams/${name}/publish" "$body"
}

# ---------------------------------------------------------------------------
# Docker container helpers on target host
# ---------------------------------------------------------------------------
list_aether_containers() {
    remote_exec "docker ps --filter 'name=aether-' --format '{{.Names}}'"
}

container_running() {
    local name="$1"
    remote_exec "docker ps --filter 'name=${name}' --filter 'status=running' -q" 2>/dev/null | grep -q .
}

# ---------------------------------------------------------------------------
# Deployment operations (unified)
# ---------------------------------------------------------------------------
deploy_start() {
    local coords="$1" strategy="$2"; shift 2
    log_info "Starting ${strategy} deployment: ${coords}" >&2
    aether_failover deploy "$coords" --"$strategy" "$@"
}

deploy_list() {
    aether_failover deploy list --format json
}

deploy_status() {
    local deployment_id="$1"
    aether_failover deploy status "$deployment_id" --format json
}

deploy_promote() {
    local deployment_id="$1"; shift
    log_info "Promoting deployment: ${deployment_id}" >&2
    aether_failover deploy promote "$deployment_id" "$@"
}

deploy_rollback() {
    local deployment_id="$1"
    log_info "Rolling back deployment: ${deployment_id}" >&2
    aether_failover deploy rollback "$deployment_id"
}

deploy_complete() {
    local deployment_id="$1"
    log_info "Completing deployment: ${deployment_id}" >&2
    aether_failover deploy complete "$deployment_id"
}

deploy_cleanup() {
    # Complete or rollback any active deployments
    local deployments
    deployments=$(deploy_list 2>/dev/null)
    echo "$deployments" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    for d in data if isinstance(data, list) else data.get('deployments', []):
        did = d.get('deploymentId', '')
        state = d.get('state', '')
        if did and state not in ('COMPLETED', 'ROLLED_BACK', 'FAILED'):
            print(did)
except: pass
" 2>/dev/null | while read -r did; do
        aether_failover deploy complete "$did" > /dev/null 2>&1 || \
        aether_failover deploy rollback "$did" > /dev/null 2>&1 || true
    done
}

# Extract deployment ID from the most recent entry in deploy list
deploy_extract_id() {
    local deployments="$1"
    echo "$deployments" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    entries = data if isinstance(data, list) else data.get('deployments', [])
    if entries:
        print(entries[0].get('deploymentId', ''))
except: pass
" 2>/dev/null
}
