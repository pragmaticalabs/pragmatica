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
    local response
    response=$(direct_api_get "/api/cluster/topology" 2>/dev/null)
    json_value "$response" "coreCount" 2>/dev/null || echo "0"
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
    aether_json status 2>/dev/null
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
# Endpoint discovery
# ---------------------------------------------------------------------------

# Discover LB endpoints from cluster status API.
# Sets LB_APP_ENDPOINT and LB_MGMT_ENDPOINT from the elected LB node info.
# Falls back to direct node access if no LB is configured.
discover_endpoints() {
    local cluster_endpoint="$1"
    local status
    status=$(curl -s -H "X-API-Key: ${API_KEY}" "${cluster_endpoint}/api/cluster/status" 2>/dev/null || true)

    if [ -n "$status" ]; then
        LB_APP_ENDPOINT=$(json_value "$status" "appEndpoint")
        LB_MGMT_ENDPOINT=$(json_value "$status" "mgmtEndpoint")
    fi

    # Fallback to direct node access if no LB
    if [ -z "$LB_APP_ENDPOINT" ]; then
        LB_APP_ENDPOINT="$cluster_endpoint"
        LB_MGMT_ENDPOINT="$cluster_endpoint"
    fi
}

wait_for_lb_ready() {
    local endpoint="$1"
    local timeout="${2:-120}"
    wait_for "LB ready at ${endpoint}" \
        "curl -sf ${endpoint}/health/live >/dev/null 2>&1" \
        "$timeout"
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
        "[ \$(json_value \"\$(curl -sf -H 'X-API-Key: ${API_KEY}' http://${TARGET_HOST}:${MGMT_PORT}/api/health 2>/dev/null)\" connectedPeers 2>/dev/null || echo 0) -ge 2 ]" \
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
    # Count running instances (LOADED or ACTIVE state)
    local count
    count=$(printf '%s' "$slices" | grep -o '"state"[[:space:]]*:[[:space:]]*"[LA][CO][AT][DI][EV][DE]*"' | wc -l | tr -d ' ')
    echo "${count:-0}"
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

publish_blueprint() {
    # Registers a blueprint in the cluster registry without making it active.
    # Required when starting a strategy-based deploy upgrade — the upgrade target
    # version must be in the registry, but should NOT be the currently active
    # version (otherwise SameVersionDeployment is returned).
    local artifact="$1"
    log_info "Publishing blueprint (no instances): ${artifact}" >&2
    api_post "/api/blueprint/publish" "{\"artifact\":\"${artifact}\"}"
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
    if [ "$CLOUD_MODE" = "true" ]; then
        # Cloud: each VM runs a single container named "aether-node"
        cloud_ssh "$node_id" "docker kill aether-node" 2>/dev/null
    else
        remote_exec "docker kill aether-${node_id}" 2>/dev/null
    fi
}

start_node() {
    local node_id="$1"
    log_info "Starting node: ${node_id}"
    if [ "$CLOUD_MODE" = "true" ]; then
        cloud_ssh "$node_id" "docker start aether-node" 2>/dev/null
    else
        remote_exec "docker start aether-${node_id}" 2>/dev/null
    fi
}

# Restart all containers for clean cluster formation (hard reset — stops everything first)
restart_all_nodes() {
    log_info "Restoring cluster to baseline..."
    if [ "$CLOUD_MODE" = "true" ]; then
        for i in $(seq 1 "$NODE_COUNT"); do
            cloud_ssh "node-${i}" "docker restart aether-node" 2>/dev/null || true
        done
    else
        remote_exec "docker ps -a --filter 'name=aether-node-' -q | xargs -r docker stop 2>/dev/null; docker rm -f \$(docker ps -a -q --filter name=aether-core) 2>/dev/null; docker ps -a --filter 'name=aether-node-' -q | xargs -r docker start" 2>/dev/null
    fi
}

# Lightweight restore: remove CTM-provisioned containers and start any stopped compose nodes.
# Only acts when the cluster is actually degraded (stopped nodes or CTM containers present).
# Restarts LB to re-establish QUIC connections when nodes were restored.
restore_baseline() {
    if [ "$CLOUD_MODE" = "true" ]; then
        return 0
    fi
    local needs_restore=false
    # Check for CTM-provisioned containers
    local ctm_count
    ctm_count=$(remote_exec "docker ps -a -q --filter name=aether-core 2>/dev/null | wc -l" 2>/dev/null)
    if [ "${ctm_count:-0}" -gt 0 ] 2>/dev/null; then
        needs_restore=true
        remote_exec "docker rm -f \$(docker ps -a -q --filter name=aether-core) 2>/dev/null || true" 2>/dev/null
    fi
    # Check for stopped compose nodes
    local stopped
    stopped=$(remote_exec "docker ps -a --filter 'name=aether-node-' --filter 'status=exited' -q 2>/dev/null | wc -l" 2>/dev/null)
    if [ "${stopped:-0}" -gt 0 ] 2>/dev/null; then
        needs_restore=true
        remote_exec "docker ps -a --filter 'name=aether-node-' --filter 'status=exited' -q | xargs -r docker start" 2>/dev/null
    fi
    if [ "$needs_restore" = true ]; then
        log_info "Cluster was degraded — waiting for nodes to rejoin"
    fi
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
    local escaped_toml
    escaped_toml=$(escape_json "$toml_content")
    json_body="{\"tomlContent\":\"${escaped_toml}\",\"expectedVersion\":0}"
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
    # Targets the consensus leader directly via its management port. CTM (cluster
    # topology manager) is leader-bound, so /api/cluster/scale must reach the leader
    # for auto-provisioning to actually run.
    local path="$1"
    local body="${2:-"{}"}"
    if [ "$CLOUD_MODE" = "true" ]; then
        # Cloud: SSH-tunnel to the leader via bastion
        local leader
        leader=$(cluster_leader)
        if [ -z "$leader" ] || [ "$leader" = "none" ]; then
            log_warn "No leader available, falling back to api_post" >&2
            api_post "$path" "$body"
            return
        fi
        local leader_ip
        leader_ip=$(cloud_node_ip "$leader")
        # Use SSH tunnel for the request
        cloud_ssh "$leader" "curl -sf -X POST -H 'X-API-Key: ${API_KEY}' -H 'Content-Type: application/json' -d '${body}' http://localhost:8080${path}" 2>/dev/null
        return
    fi
    local leader
    leader=$(cluster_leader)
    if [ -z "$leader" ] || [ "$leader" = "none" ]; then
        log_warn "No leader available, falling back to direct_api_post" >&2
        direct_api_post "$path" "$body"
        return
    fi
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

schema_migrate() {
    local datasource="$1"
    api_post "/api/schema/migrate/${datasource}" "{}"
}

schema_retry() {
    local datasource="$1"
    api_post "/api/schema/retry/${datasource}" "{}"
}

schema_history() {
    local datasource="$1"
    api_get "/api/schema/history/${datasource}"
}

schema_baseline() {
    local datasource="$1"
    api_post "/api/schema/baseline/${datasource}" "{}"
}

schema_undo() {
    local datasource="$1"
    api_post "/api/schema/undo/${datasource}" "{}"
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
    api_post "/api/streams/publish/${name}" "$body"
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
    printf '%s' "$tasks" | grep -o '"group"' | wc -l | tr -d ' '
}

task_group_status() {
    local group="$1"
    local tasks
    tasks=$(cluster_tasks)
    # Extract status for the matching group from JSON
    printf '%s' "$tasks" | grep -o "\"group\"[[:space:]]*:[[:space:]]*\"${group}\"[^}]*\"status\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -1 | grep -o '"status"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*"status"[[:space:]]*:[[:space:]]*"//' | sed 's/"$//' || echo "UNASSIGNED"
}

task_group_node() {
    local group="$1"
    local tasks
    tasks=$(cluster_tasks)
    printf '%s' "$tasks" | grep -o "\"group\"[[:space:]]*:[[:space:]]*\"${group}\"[^}]*\"assignedTo\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -1 | grep -o '"assignedTo"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*"assignedTo"[[:space:]]*:[[:space:]]*"//' | sed 's/"$//'
}

reassign_task_group() {
    # TaskAssignmentCoordinator is leader-bound, so we must hit the leader directly.
    local group="$1" target="$2"
    local leader
    leader=$(cluster_leader)
    if [ -z "$leader" ] || [ "$leader" = "none" ]; then
        log_warn "No leader available for reassign" >&2
        return 1
    fi
    local node_num
    node_num=$(echo "$leader" | sed 's/node-//')
    local port=$((MGMT_PORT + node_num - 1))
    curl -sf -X PUT -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
        -d "{\"targetNode\":\"${target}\"}" \
        "http://${TARGET_HOST}:${port}/api/cluster/tasks/reassign/${group}"
}

wait_for_all_tasks_active() {
    local timeout="${1:-60}"
    local min_active="${2:-5}"
    wait_for "all task groups ACTIVE" \
        "[ \$(json_count_matching \"\$(cluster_tasks)\" assignments status ACTIVE 2>/dev/null || echo 0) -ge ${min_active} ]" \
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
    # Compose the strategy body that DeployCommand would build (extra args ignored — CLI
    # only passes them through HTTP body, the bash test layer just composes JSON itself).
    local strategy_upper instances=2 traffic=10 manual=false
    case "$strategy" in
        blue-green) strategy_upper="BLUE_GREEN" ;;
        canary) strategy_upper="CANARY" ;;
        rolling) strategy_upper="ROLLING" ;;
        *) strategy_upper=$(echo "$strategy" | tr '[:lower:]' '[:upper:]') ;;
    esac
    while [ $# -gt 0 ]; do
        case "$1" in
            --instances) instances="$2"; shift 2 ;;
            --traffic) traffic="$2"; shift 2 ;;
            --manual-approval) manual=true; shift ;;
            *) shift ;;
        esac
    done
    local strategy_body
    case "$strategy_upper" in
        BLUE_GREEN)
            strategy_body="\"blueGreen\":{\"drainTimeoutMs\":30000}" ;;
        CANARY)
            strategy_body="\"canary\":{\"stages\":[{\"trafficPercent\":${traffic},\"observationMinutes\":10}]}" ;;
        ROLLING)
            strategy_body="\"rolling\":{\"requireManualApproval\":${manual}}" ;;
    esac
    local body="{\"blueprint\":\"${coords}\",\"strategy\":\"${strategy_upper}\",\"instances\":${instances},${strategy_body},\"thresholds\":{\"maxErrorRate\":0.1,\"maxLatencyMs\":1000}}"
    api_post "/api/deploy" "$body"
}

deploy_list() {
    api_get "/api/deploy"
}

deploy_status() {
    local deployment_id="$1"
    api_get "/api/deploy/${deployment_id}"
}

deploy_promote() {
    local deployment_id="$1"
    log_info "Promoting deployment: ${deployment_id}" >&2
    api_post "/api/deploy/promote/${deployment_id}" "{}"
}

deploy_rollback() {
    local deployment_id="$1"
    log_info "Rolling back deployment: ${deployment_id}" >&2
    api_post "/api/deploy/rollback/${deployment_id}" "{}"
}

deploy_complete() {
    local deployment_id="$1"
    log_info "Completing deployment: ${deployment_id}" >&2
    api_post "/api/deploy/complete/${deployment_id}" "{}"
}

deploy_cleanup() {
    # Complete or rollback any active deployments via the LB management endpoint.
    local deployments
    deployments=$(deploy_list 2>/dev/null)
    # Extract deployment IDs that are not in terminal states
    printf '%s' "$deployments" | grep -o '"deploymentId"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*"deploymentId"[[:space:]]*:[[:space:]]*"//' | sed 's/"$//' | while read -r did; do
        # Skip if in terminal state (check the surrounding context)
        printf '%s' "$deployments" | grep -q "\"deploymentId\"[[:space:]]*:[[:space:]]*\"${did}\"[^}]*\"state\"[[:space:]]*:[[:space:]]*\"COMPLETED\"" && continue
        printf '%s' "$deployments" | grep -q "\"deploymentId\"[[:space:]]*:[[:space:]]*\"${did}\"[^}]*\"state\"[[:space:]]*:[[:space:]]*\"ROLLED_BACK\"" && continue
        printf '%s' "$deployments" | grep -q "\"deploymentId\"[[:space:]]*:[[:space:]]*\"${did}\"[^}]*\"state\"[[:space:]]*:[[:space:]]*\"FAILED\"" && continue
        echo "$did"
    done | while read -r did; do
        deploy_complete "$did" > /dev/null 2>&1 || \
        deploy_rollback "$did" > /dev/null 2>&1 || true
    done
    sleep 1
}

# Extract deployment ID from the most recent entry in deploy list
deploy_extract_id() {
    local deployments="$1"
    json_value "$deployments" "deploymentId"
}

# ---------------------------------------------------------------------------
# Self-Heal (dual-cluster support)
# ---------------------------------------------------------------------------

# Wait for specific node count on a given endpoint
wait_for_node_count_on() {
    local endpoint="$1"
    local expected="$2"
    local timeout="${3:-120}"

    wait_for "${expected} nodes on ${endpoint}" \
        "[ \$(json_value \"\$(curl -sf -H 'X-API-Key: ${API_KEY}' ${endpoint}/api/cluster/topology 2>/dev/null)\" coreCount 2>/dev/null || echo 0) -ge ${expected} ]" \
        "$timeout"
}

# Wait for leader election on a given endpoint
wait_for_leader_on() {
    local endpoint="$1"
    local timeout="${2:-30}"

    wait_for "leader elected on ${endpoint}" \
        "json_contains \"\$(curl -sf -H 'X-API-Key: ${API_KEY}' ${endpoint}/api/cluster/topology 2>/dev/null)\" role ACTIVE" \
        "$timeout"
}

# Self-heal: wait for cluster to recover after destructive test.
# Usage: self_heal <env_type> <compose_file> <expected_node_count> <mgmt_endpoint>
self_heal() {
    local env_type="$1"
    local compose_file="$2"
    local expected_count="${3:-5}"
    local endpoint="${4:-${CLUSTER_B_MGMT}}"

    log_info "Self-heal: waiting for ${expected_count} healthy nodes..."

    # Step 1: wait for natural recovery (CTM auto-heal)
    if wait_for_node_count_on "$endpoint" "$expected_count" 120; then
        wait_for_leader_on "$endpoint" 30 && return 0
    fi

    # Step 2: force restart
    log_warn "Cluster did not self-heal within 120s, forcing restart"
    case "$env_type" in
        docker|remote)
            docker compose -f "$compose_file" restart 2>/dev/null
            # Kill any orphaned CTM-provisioned containers
            docker rm -f $(docker ps -aq --filter "name=aether-core") 2>/dev/null || true
            ;;
        cloud)
            aether cluster heal --cluster cluster-b 2>/dev/null || true
            ;;
    esac

    if wait_for_node_count_on "$endpoint" "$expected_count" 120; then
        wait_for_leader_on "$endpoint" 30 && return 0
    fi

    # Step 3: abort
    log_error "Cluster unrecoverable after restart -- aborting destructive suites"
    return 1
}
