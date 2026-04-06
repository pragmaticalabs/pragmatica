#!/bin/bash
# cluster.sh — Cluster lifecycle operations for Aether integration tests

LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${LIB_DIR}/common.sh"

# ---------------------------------------------------------------------------
# Cluster queries (CLI-based)
# ---------------------------------------------------------------------------
cluster_node_count() {
    # Query core node topology directly — LB's topology may not see provisioned nodes.
    # Uses coreCount which excludes passive nodes (LB).
    direct_api_get "/api/cluster/topology" \
        | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('coreCount',0))" 2>/dev/null \
        || echo "0"
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
        local candidate="node-$i"
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

# Wait for cluster using direct node access (before LB is available)
wait_for_cluster_direct() {
    wait_for "cluster healthy (direct)" \
        "curl -sf -H 'X-API-Key: ${API_KEY}' http://${TARGET_HOST}:${MGMT_PORT}/api/health 2>/dev/null | python3 -c 'import sys,json; d=json.load(sys.stdin); exit(0 if d.get(\"connectedPeers\",0)+1 >= 3 else 1)' 2>/dev/null" \
        "${1:-120}"
}

wait_for_node_count() {
    local expected="$1" timeout="${2:-120}"
    wait_for "${expected} nodes" "[ \$(cluster_node_count) -eq ${expected} ]" "$timeout"
}

wait_for_leader() {
    wait_for "leader elected" "[ -n \"\$(cluster_leader)\" ] && [ \"\$(cluster_leader)\" != 'none' ]" "${1:-60}"
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
    remote_exec "docker ps -a --filter 'name=aether-node-' -q | xargs -r docker restart" 2>/dev/null
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

drain_node() {
    local node_id="$1"
    api_post "/api/node/drain/${node_id}" "{}"
}

activate_node() {
    local node_id="$1"
    api_post "/api/node/activate/${node_id}" "{}"
}

# ---------------------------------------------------------------------------
# Scaling
# ---------------------------------------------------------------------------

# Seed cluster config into KV-Store if not already present.
# Required before scale operations — the scale API reads ClusterConfigValue from KV-Store.
seed_cluster_config() {
    local config_file="${1:-${LIB_DIR}/../cluster-config.toml}"
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/cluster/config" \
        -H "X-API-Key: ${API_KEY}")
    if [ "$status" = "200" ]; then
        log_info "Cluster config already present"
        return 0
    fi
    log_info "Seeding cluster config from ${config_file}"
    local toml_content
    toml_content=$(cat "$config_file")
    local json_body
    json_body=$(python3 -c "import sys,json; print(json.dumps({'tomlContent': sys.stdin.read(), 'expectedVersion': 0}))" <<< "$toml_content")
    # Must hit the leader — CTM only runs on leader
    leader_api_post "/api/cluster/config" "$json_body"
}

scale_cluster() {
    local target="$1"
    local leader
    leader=$(cluster_leader)
    log_info "Scaling cluster to ${target} nodes (leader: ${leader})" >&2
    # Must hit the leader — CTM.setDesiredSize() only activates on leader
    local result
    result=$(leader_api_post "/api/cluster/scale" "{\"coreCount\":${target},\"expectedVersion\":0}")
    log_info "Scale result: ${result}" >&2
}

# POST to the leader node — finds leader via CLI, targets its management port
leader_api_post() {
    local path="$1"
    local body="${2:-"{}"}"
    local leader
    leader=$(cluster_leader)
    if [ -z "$leader" ] || [ "$leader" = "none" ]; then
        log_warn "No leader available, falling back to direct_api_post" >&2
        direct_api_post "$path" "$body"
        return
    fi
    # Derive port from leader node ID (node-N → MGMT_PORT + N-1)
    local node_num
    node_num=$(echo "$leader" | sed 's/node-//')
    local port=$((MGMT_PORT + node_num - 1))
    curl -sf -X POST -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
        -d "$body" "http://${TARGET_HOST}:${port}${path}"
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
# Task Delegation
# ---------------------------------------------------------------------------
cluster_tasks() {
    api_get "/api/cluster/tasks"
}

task_assignment_count() {
    local tasks
    tasks=$(cluster_tasks)
    echo "$tasks" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(len(data.get('assignments', [])))
except:
    print(0)
" 2>/dev/null
}

task_group_status() {
    local group="$1"
    local tasks
    tasks=$(cluster_tasks)
    echo "$tasks" | python3 -c "
import sys, json
result = 'ERROR'
try:
    data = json.load(sys.stdin)
    result = 'UNASSIGNED'
    for a in data.get('assignments', []):
        if a.get('group') == '${group}':
            result = a.get('status', 'UNKNOWN')
            break
except Exception:
    pass
print(result, end='')
" 2>/dev/null
}

task_group_node() {
    local group="$1"
    local tasks
    tasks=$(cluster_tasks)
    echo "$tasks" | python3 -c "
import sys, json
result = ''
try:
    data = json.load(sys.stdin)
    for a in data.get('assignments', []):
        if a.get('group') == '${group}':
            result = a.get('assignedTo', '')
            break
except Exception:
    pass
print(result, end='')
" 2>/dev/null
}

reassign_task_group() {
    local group="$1" target="$2"
    api_put "/api/cluster/tasks/${group}/reassign" "{\"targetNode\":\"${target}\"}"
}

wait_for_all_tasks_active() {
    local timeout="${1:-60}"
    wait_for "all task groups ACTIVE" \
        "[ \$(cluster_tasks | python3 -c \"import sys,json; data=json.load(sys.stdin); print(sum(1 for a in data.get('assignments',[]) if a.get('status')=='ACTIVE'))\" 2>/dev/null) -ge 6 ]" \
        "$timeout"
}

wait_for_task_active() {
    local group="$1" timeout="${2:-30}"
    wait_for "task group ${group} ACTIVE" \
        "[ \"\$(task_group_status ${group})\" = 'ACTIVE' ]" \
        "$timeout"
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
