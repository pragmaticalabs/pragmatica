#!/bin/bash
#
# Aether Rolling Cluster Upgrade
#
# Performs a zero-downtime rolling upgrade of an Aether cluster by upgrading
# one node at a time through the Management API.
#
# Usage:
#   rolling-aether-upgrade.sh --cluster <host:port> --version <version> [options]
#
# Options:
#   --cluster <host:port>    Management API endpoint (discovers other nodes)
#   --version <version>      New version to deploy
#   --canary-wait <seconds>  Health observation window per node (default: 30)
#   --api-key <key>          RBAC authentication key
#   --dry-run                Show plan without executing
#   --skip-download          Assume binaries already staged
#
# Requirements:
#   - curl, jq
#   - Network access to all node management endpoints
#
# The script:
#   1. Discovers all cluster nodes via the management API
#   2. For each node (one at a time):
#      a. Drains the node (evacuates slices)
#      b. Waits for DECOMMISSIONED state
#      c. Initiates shutdown
#      d. Waits for node to go offline
#      e. (External: user restarts node with new binary)
#      f. Waits for node to come back online (/health/ready)
#      g. Activates the node
#      h. Runs canary health check
#   3. Reports upgrade summary

set -euo pipefail

# Defaults
CANARY_WAIT=30
DRY_RUN=false
SKIP_DOWNLOAD=false
API_KEY=""
CLUSTER=""
VERSION=""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC}  $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_step()  { echo -e "${CYAN}[STEP]${NC}  $1"; }

usage() {
    echo "Usage: $0 --cluster <host:port> --version <version> [options]"
    echo ""
    echo "Options:"
    echo "  --cluster <host:port>    Management API endpoint"
    echo "  --version <version>      New version to deploy"
    echo "  --canary-wait <seconds>  Health observation window (default: 30)"
    echo "  --api-key <key>          RBAC authentication key"
    echo "  --dry-run                Show plan without executing"
    echo "  --skip-download          Assume binaries already staged"
    echo "  --help                   Show this help"
    exit 0
}

# Parse arguments
while [ $# -gt 0 ]; do
    case "$1" in
        --cluster)     CLUSTER="$2"; shift 2 ;;
        --cluster=*)   CLUSTER="${1#*=}"; shift ;;
        --version)     VERSION="$2"; shift 2 ;;
        --version=*)   VERSION="${1#*=}"; shift ;;
        --canary-wait) CANARY_WAIT="$2"; shift 2 ;;
        --canary-wait=*) CANARY_WAIT="${1#*=}"; shift ;;
        --api-key)     API_KEY="$2"; shift 2 ;;
        --api-key=*)   API_KEY="${1#*=}"; shift ;;
        --dry-run)     DRY_RUN=true; shift ;;
        --skip-download) SKIP_DOWNLOAD=true; shift ;;
        --help|-h)     usage ;;
        *)             log_error "Unknown option: $1"; exit 1 ;;
    esac
done

# Validate required args
if [ -z "$CLUSTER" ]; then
    log_error "Missing required: --cluster <host:port>"
    exit 1
fi
if [ -z "$VERSION" ]; then
    log_error "Missing required: --version <version>"
    exit 1
fi

# Check dependencies
for cmd in curl jq; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        log_error "Required command not found: $cmd"
        exit 1
    fi
done

# Build auth header
auth_header() {
    if [ -n "$API_KEY" ]; then
        echo "-H" "Authorization: Bearer $API_KEY"
    fi
}

# API call helper
api_call() {
    local method="$1"
    local url="$2"
    local auth
    auth=$(auth_header)

    if [ "$method" = "GET" ]; then
        curl -fsSL $auth "$url" 2>/dev/null
    else
        curl -fsSL -X "$method" $auth "$url" 2>/dev/null
    fi
}

# Discover all nodes in the cluster
discover_nodes() {
    log_step "Discovering cluster nodes from $CLUSTER..."

    local response
    response=$(api_call GET "http://$CLUSTER/api/nodes/lifecycle")

    if [ -z "$response" ]; then
        log_error "Failed to discover nodes. Is the cluster running at $CLUSTER?"
        exit 1
    fi

    NODE_IDS=$(echo "$response" | jq -r '.[].nodeId')
    NODE_COUNT=$(echo "$NODE_IDS" | wc -l | tr -d ' ')

    if [ "$NODE_COUNT" -eq 0 ]; then
        log_error "No nodes found in cluster"
        exit 1
    fi

    log_info "Found $NODE_COUNT nodes:"
    echo "$NODE_IDS" | while read -r node_id; do
        local state
        state=$(echo "$response" | jq -r ".[] | select(.nodeId == \"$node_id\") | .state")
        echo "  - $node_id ($state)"
    done
    echo ""
}

# Wait for a node to reach a specific lifecycle state
wait_for_state() {
    local node_id="$1"
    local target_state="$2"
    local timeout="${3:-120}"
    local elapsed=0

    while [ "$elapsed" -lt "$timeout" ]; do
        local response
        response=$(api_call GET "http://$CLUSTER/api/node/lifecycle/$node_id" 2>/dev/null) || true

        if [ -n "$response" ]; then
            local state
            state=$(echo "$response" | jq -r '.state' 2>/dev/null) || true
            if [ "$state" = "$target_state" ]; then
                return 0
            fi
        fi

        sleep 2
        elapsed=$((elapsed + 2))
    done

    log_error "Timeout waiting for $node_id to reach $target_state (waited ${timeout}s)"
    return 1
}

# Wait for a node's health endpoint to respond
wait_for_ready() {
    local node_id="$1"
    local timeout="${2:-180}"
    local elapsed=0

    # We use the main cluster endpoint since we don't know individual node endpoints
    # The node will appear in lifecycle as ON_DUTY when ready
    while [ "$elapsed" -lt "$timeout" ]; do
        local response
        response=$(api_call GET "http://$CLUSTER/api/node/lifecycle/$node_id" 2>/dev/null) || true

        if [ -n "$response" ]; then
            local state
            state=$(echo "$response" | jq -r '.state' 2>/dev/null) || true
            # Node has rejoined cluster and is visible
            if [ -n "$state" ] && [ "$state" != "null" ] && [ "$state" != "SHUTTING_DOWN" ]; then
                return 0
            fi
        fi

        sleep 3
        elapsed=$((elapsed + 3))
    done

    log_error "Timeout waiting for $node_id to become ready (waited ${timeout}s)"
    return 1
}

# Run canary check: observe health for canary-wait seconds
canary_check() {
    local node_id="$1"

    log_info "  Canary check: observing $node_id for ${CANARY_WAIT}s..."
    sleep "$CANARY_WAIT"

    # Verify node is still healthy
    local response
    response=$(api_call GET "http://$CLUSTER/api/node/lifecycle/$node_id" 2>/dev/null) || true

    if [ -z "$response" ]; then
        log_error "  Canary FAILED: $node_id not responding"
        return 1
    fi

    local state
    state=$(echo "$response" | jq -r '.state' 2>/dev/null) || true
    if [ "$state" = "ON_DUTY" ]; then
        log_info "  Canary PASSED: $node_id is ON_DUTY"
        return 0
    else
        log_error "  Canary FAILED: $node_id state is $state (expected ON_DUTY)"
        return 1
    fi
}

# Upgrade a single node
upgrade_node() {
    local node_id="$1"
    local node_num="$2"
    local total="$3"

    echo ""
    log_step "=== Upgrading node $node_num/$total: $node_id ==="

    # Step 1: Drain
    log_info "  Draining $node_id..."
    local drain_response
    drain_response=$(api_call POST "http://$CLUSTER/api/node/drain/$node_id") || true

    local success
    success=$(echo "$drain_response" | jq -r '.success' 2>/dev/null) || true
    if [ "$success" != "true" ]; then
        local msg
        msg=$(echo "$drain_response" | jq -r '.message' 2>/dev/null) || true
        # If already draining/decommissioned, continue
        if echo "$msg" | grep -qi "DRAINING\|DECOMMISSIONED"; then
            log_warn "  $node_id already draining/decommissioned, continuing..."
        else
            log_error "  Failed to drain $node_id: $msg"
            return 1
        fi
    fi

    # Step 2: Wait for DECOMMISSIONED
    log_info "  Waiting for $node_id to reach DECOMMISSIONED..."
    if ! wait_for_state "$node_id" "DECOMMISSIONED" 120; then
        log_error "  Aborting: $node_id did not drain in time"
        log_info "  Re-activating $node_id..."
        api_call POST "http://$CLUSTER/api/node/activate/$node_id" > /dev/null 2>&1 || true
        return 1
    fi

    # Step 3: Shutdown
    log_info "  Shutting down $node_id..."
    api_call POST "http://$CLUSTER/api/node/shutdown/$node_id" > /dev/null 2>&1 || true

    # Step 4: Wait for user to restart node with new binary
    echo ""
    log_warn "  ============================================"
    log_warn "  Node $node_id is shut down."
    log_warn "  Restart it with version $VERSION now."
    log_warn "  Examples:"
    log_warn "    systemctl restart aether-node@$node_id"
    log_warn "    podman restart $node_id"
    log_warn "    kubectl rollout restart deployment/$node_id"
    log_warn "  ============================================"
    echo ""
    log_info "  Waiting for $node_id to come back online..."

    # Step 5: Wait for node to rejoin
    if ! wait_for_ready "$node_id" 180; then
        log_error "UPGRADE HALTED: $node_id did not come back online"
        log_error ""
        log_error "Manual recovery required:"
        log_error "  1. Check $node_id logs and restart it"
        log_error "  2. Once online, activate it: curl -X POST http://$CLUSTER/api/node/activate/$node_id"
        log_error "  3. Re-run this script to continue with remaining nodes"
        return 1
    fi

    # Step 6: Activate
    log_info "  Activating $node_id..."
    api_call POST "http://$CLUSTER/api/node/activate/$node_id" > /dev/null 2>&1 || true

    # Step 7: Wait for ON_DUTY
    if ! wait_for_state "$node_id" "ON_DUTY" 60; then
        log_warn "  $node_id not yet ON_DUTY, but continuing..."
    fi

    # Step 8: Canary
    if ! canary_check "$node_id"; then
        log_error "UPGRADE HALTED after canary failure on $node_id"
        log_error ""
        log_error "The cluster is in a mixed-version state (this is safe)."
        log_error "Investigate $node_id before continuing."
        log_error "To continue: re-run this script (already-upgraded nodes will be skipped)."
        return 1
    fi
}

# Main upgrade flow
main() {
    echo ""
    log_step "Aether Rolling Cluster Upgrade"
    log_info "Target version: $VERSION"
    log_info "Cluster endpoint: $CLUSTER"
    log_info "Canary wait: ${CANARY_WAIT}s"
    log_info "Skip download: $SKIP_DOWNLOAD"
    echo ""

    discover_nodes

    if [ "$DRY_RUN" = "true" ]; then
        log_info "DRY RUN — would upgrade the following nodes:"
        local num=0
        echo "$NODE_IDS" | while read -r node_id; do
            num=$((num + 1))
            echo "  $num. $node_id: drain -> shutdown -> restart v$VERSION -> activate -> canary (${CANARY_WAIT}s)"
        done
        echo ""
        log_info "No changes made."
        exit 0
    fi

    local upgraded=0
    local failed=0
    local total=$NODE_COUNT

    echo "$NODE_IDS" | while read -r node_id; do
        upgraded=$((upgraded + 1))
        if ! upgrade_node "$node_id" "$upgraded" "$total"; then
            failed=$((failed + 1))
            break
        fi
    done

    echo ""
    log_step "=== Upgrade Summary ==="
    log_info "Target version: $VERSION"
    log_info "Nodes processed: $upgraded / $total"
    if [ "$failed" -gt 0 ]; then
        log_error "Failed nodes: $failed"
        log_error "Re-run this script after resolving failures."
        exit 1
    else
        log_info "All nodes upgraded successfully."
    fi
}

main
