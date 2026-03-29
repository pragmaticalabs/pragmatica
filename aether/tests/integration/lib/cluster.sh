#!/bin/bash
# cluster.sh — Cluster lifecycle operations for Aether integration tests

LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${LIB_DIR}/common.sh"

# ---------------------------------------------------------------------------
# Cluster queries (CLI-based)
# ---------------------------------------------------------------------------
cluster_node_count() {
    aether_field status cluster.nodeCount
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

deploy_blueprint() {
    local artifact="$1"
    log_info "Deploying blueprint: ${artifact}"
    api_post "/api/blueprint/deploy" "{\"artifact\":\"${artifact}\"}"
}

deploy_blueprint_file() {
    local filepath="$1"
    log_info "Deploying blueprint file: ${filepath}"
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
    log_info "Scaling cluster to ${target} nodes"
    api_post "/api/scale" "{\"targetNodes\":${target}}"
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
# Deployment strategies
# ---------------------------------------------------------------------------
rolling_update_start() {
    local body="$1"
    log_info "Starting rolling update"
    api_post "/api/rolling-update/start" "$body"
}

rolling_update_status() {
    local update_id="$1"
    api_get "/api/rolling-update/${update_id}"
}

rolling_update_complete() {
    local update_id="$1"
    api_post "/api/rolling-update/${update_id}/complete" "{}"
}

rolling_update_rollback() {
    local update_id="$1"
    api_post "/api/rolling-update/${update_id}/rollback" "{}"
}

canary_start() {
    local body="$1"
    log_info "Starting canary deployment"
    api_post "/api/canary/start" "$body"
}

canary_status() {
    local canary_id="$1"
    api_get "/api/canary/${canary_id}"
}

canary_promote() {
    local canary_id="$1" percentage="${2:-100}"
    api_post "/api/canary/${canary_id}/promote" "{\"percentage\":${percentage}}"
}

canary_promote_full() {
    local canary_id="$1"
    api_post "/api/canary/${canary_id}/promote-full" "{}"
}

canary_rollback() {
    local canary_id="$1"
    api_post "/api/canary/${canary_id}/rollback" "{}"
}

blue_green_deploy() {
    local body="$1"
    log_info "Starting blue-green deployment"
    api_post "/api/blue-green/deploy" "$body"
}

blue_green_status() {
    local deployment_id="$1"
    api_get "/api/blue-green/${deployment_id}"
}

blue_green_switch() {
    local deployment_id="$1"
    api_post "/api/blue-green/${deployment_id}/switch" "{}"
}

blue_green_switch_back() {
    local deployment_id="$1"
    api_post "/api/blue-green/${deployment_id}/switch-back" "{}"
}

blue_green_complete() {
    local deployment_id="$1"
    api_post "/api/blue-green/${deployment_id}/complete" "{}"
}

# ---------------------------------------------------------------------------
# Schema
# ---------------------------------------------------------------------------
schema_status() {
    local datasource="${1:-}"
    if [ -n "$datasource" ]; then
        api_get "/api/schema/status/${datasource}"
    else
        api_get "/api/schema/status"
    fi
}

schema_migrate() {
    local datasource="$1"
    api_post "/api/schema/migrate/${datasource}" "{}"
}

schema_retry() {
    local datasource="$1"
    api_post "/api/schema/retry/${datasource}" "{}"
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
# Passive Load Balancer
# ---------------------------------------------------------------------------
LB_CONTAINER="${LB_CONTAINER:-aether-integration-lb}"
LB_IMAGE="${LB_IMAGE:-aether-lb:local}"
LB_CLUSTER_PORT="${LB_CLUSTER_PORT:-7000}"

start_lb() {
    local peers=""
    for i in $(seq 1 "${NODE_COUNT}"); do
        [ -n "$peers" ] && peers="${peers},"
        peers="${peers}aether-integration-test-${i}:6000"
    done

    log_info "Starting passive LB: ${LB_CONTAINER}"
    log_info "  Peers: ${peers}"
    log_info "  HTTP port: ${LB_PORT} (mapped to host)"

    remote_exec "docker rm -f ${LB_CONTAINER} 2>/dev/null; \
        docker run -d \
            --name ${LB_CONTAINER} \
            --network aether-network \
            -p ${LB_PORT}:8080 \
            -e PEERS=${peers} \
            -e CLUSTER_PORT=${LB_CLUSTER_PORT} \
            ${LB_IMAGE}" 2>/dev/null
}

stop_lb() {
    log_info "Stopping passive LB: ${LB_CONTAINER}"
    remote_exec "docker rm -f ${LB_CONTAINER}" 2>/dev/null
}

wait_for_lb() {
    local timeout="${1:-60}"
    wait_for "LB healthy" "curl -sf ${LB_ENDPOINT}/health > /dev/null 2>&1" "$timeout"
}

is_lb_running() {
    container_running "${LB_CONTAINER}"
}
