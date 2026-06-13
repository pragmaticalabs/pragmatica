#!/bin/bash
# teardown-cloud.sh — Destroy all Hetzner cloud test resources
#
# Usage: bash aether/tests/cloud/teardown-cloud.sh [--yes]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLUSTER_NAME="cloud-test"
LABEL="aether-cluster=${CLUSTER_NAME}"
AUTO_CONFIRM=false

for arg in "$@"; do
    case "$arg" in
        --yes|-y) AUTO_CONFIRM=true ;;
    esac
done

# --- helpers ---
log()  { echo "[$(date +%H:%M:%S)] $*"; }
fail() { echo "[$(date +%H:%M:%S)] ERROR: $*" >&2; }

if [ -z "${HCLOUD_TOKEN:-}" ]; then
    fail "HCLOUD_TOKEN not set"
    exit 1
fi

# --- confirmation ---
if [ "$AUTO_CONFIRM" != true ]; then
    echo "This will destroy ALL resources labeled ${LABEL}:"
    hcloud server list --selector "$LABEL" -o columns=id,name,status 2>/dev/null || true
    hcloud load-balancer list --selector "$LABEL" -o columns=id,name 2>/dev/null || true
    hcloud network list --selector "$LABEL" -o columns=id,name 2>/dev/null || true
    hcloud ssh-key list -o columns=id,name 2>/dev/null | grep "${CLUSTER_NAME}" || true
    echo
    read -rp "Confirm teardown? [y/N] " answer
    if [[ ! "$answer" =~ ^[Yy] ]]; then
        echo "Aborted."
        exit 0
    fi
fi

START_TIME=""
if [ -f "${SCRIPT_DIR}/.cloud-env" ]; then
    START_TIME=$(grep '^DEPLOY_START=' "${SCRIPT_DIR}/.cloud-env" 2>/dev/null | cut -d= -f2 || true)
fi

# --- teardown (order matters: LB targets → LB → servers → network → SSH key) ---

log "Removing Hetzner load balancer..."
for lb_id in $(hcloud load-balancer list --selector "$LABEL" -o noheader -o columns=id 2>/dev/null); do
    hcloud load-balancer delete "$lb_id" && log "  Deleted LB $lb_id" || fail "  Failed to delete LB $lb_id"
done

log "Removing servers..."
for server_id in $(hcloud server list --selector "$LABEL" -o noheader -o columns=id 2>/dev/null); do
    hcloud server delete "$server_id" && log "  Deleted server $server_id" || fail "  Failed to delete server $server_id"
done

# Also kill any auto-provisioned nodes (aether-core-* pattern, may not have the label)
for server_id in $(hcloud server list -o noheader -o columns=id,name 2>/dev/null | grep "aether-core" | awk '{print $1}'); do
    hcloud server delete "$server_id" && log "  Deleted auto-provisioned server $server_id" || fail "  Failed to delete server $server_id"
done

log "Removing private network..."
for net_id in $(hcloud network list --selector "$LABEL" -o noheader -o columns=id 2>/dev/null); do
    hcloud network delete "$net_id" && log "  Deleted network $net_id" || fail "  Failed to delete network $net_id"
done

log "Removing SSH key..."
hcloud ssh-key delete "${CLUSTER_NAME}-key" 2>/dev/null && log "  Deleted SSH key" || log "  SSH key not found (already deleted?)"

# --- local cleanup ---
log "Removing local cluster registration..."
aether cluster unregister "$CLUSTER_NAME" 2>/dev/null || true
rm -f "${SCRIPT_DIR}/.cloud-env"

# --- cost summary ---
if [ -n "$START_TIME" ]; then
    NOW=$(date +%s)
    ELAPSED_S=$((NOW - START_TIME))
    ELAPSED_H=$(echo "scale=2; $ELAPSED_S / 3600" | bc 2>/dev/null || echo "?")
    COST=$(echo "scale=2; $ELAPSED_H * 0.071" | bc 2>/dev/null || echo "?")
    log "Cluster was up for ${ELAPSED_H} hours. Estimated cost: \$${COST}"
else
    log "No start time recorded — cannot estimate cost."
fi

# --- verify nothing remains ---
REMAINING=$(hcloud server list --selector "$LABEL" -o noheader 2>/dev/null | wc -l | tr -d ' ')
if [ "$REMAINING" -gt 0 ] 2>/dev/null; then
    fail "$REMAINING servers still exist with label $LABEL — manual cleanup needed"
    hcloud server list --selector "$LABEL"
    exit 1
fi

log "Teardown complete."
