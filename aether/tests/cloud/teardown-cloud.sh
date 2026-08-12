#!/bin/bash
# teardown-cloud.sh — Destroy all Hetzner cloud test resources
#
# Usage: bash aether/tests/cloud/teardown-cloud.sh [--yes]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLUSTER_NAME="cloud-test"
LABEL="aether-cluster=${CLUSTER_NAME}"
AUTO_CONFIRM=false

# Provider driver (RFC-0016 §5): all hcloud usage lives behind driver_<op>.
source "${SCRIPT_DIR}/lib/cloud-driver.sh"

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
    driver_reap_list_servers "$LABEL" 2>/dev/null || true
    driver_reap_list_lbs "$LABEL" 2>/dev/null || true
    driver_reap_list_networks "$LABEL" 2>/dev/null || true
    driver_reap_list_sshkeys 2>/dev/null | grep "${CLUSTER_NAME}" || true
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

# --- cost inputs, captured BEFORE anything is deleted ---
# The cost summary at the end multiplies by fleet size. That has to be read now: once the
# servers are gone, driver_server_count_by_label returns 0 and the estimate silently
# collapses back to single-node cost — which is the bug this replaces.
BILLED_NODES=$(driver_server_count_by_label "$LABEL" 2>/dev/null || echo 0)
BILLED_TYPE=$(driver_server_type_by_label "$LABEL" 2>/dev/null || echo "")
log "Cost inputs: ${BILLED_NODES} server(s), type=${BILLED_TYPE:-<unknown>}"

log "Removing Hetzner load balancer..."
for lb_id in $(driver_lb_ids_by_label "$LABEL"); do
    driver_delete_lb "$lb_id" && log "  Deleted LB $lb_id" || fail "  Failed to delete LB $lb_id"
done

log "Removing servers..."
for server_id in $(driver_server_ids_by_label "$LABEL"); do
    driver_delete_server "$server_id" && log "  Deleted server $server_id" || fail "  Failed to delete server $server_id"
done

# Also kill any auto-provisioned nodes (aether-core-* pattern, may not have the label)
for server_id in $(driver_orphan_core_server_ids); do
    driver_delete_server "$server_id" && log "  Deleted auto-provisioned server $server_id" || fail "  Failed to delete server $server_id"
done

log "Removing private network..."
for net_id in $(driver_network_ids_by_label "$LABEL"); do
    driver_delete_network "$net_id" && log "  Deleted network $net_id" || fail "  Failed to delete network $net_id"
done

log "Removing SSH key..."
driver_delete_sshkey "${CLUSTER_NAME}-key" 2>/dev/null && log "  Deleted SSH key" || log "  SSH key not found (already deleted?)"

# --- local cleanup ---
log "Removing local cluster registration..."
aether cluster unregister "$CLUSTER_NAME" 2>/dev/null || true
rm -f "${SCRIPT_DIR}/.cloud-env"

# --- cost summary ---
if [ -n "$START_TIME" ]; then
    NOW=$(date +%s)
    ELAPSED_S=$((NOW - START_TIME))
    ELAPSED_H=$(echo "scale=2; $ELAPSED_S / 3600" | bc 2>/dev/null || echo "?")
    # elapsed x per-node-hour rate x NODE COUNT. The node count was the missing factor:
    # without it a 100-node run reported the cost of one node, understating by exactly N
    # (a ~$14 run reads as ~$0.14). Auto-provisioned replacements are included because the
    # count is read from the live label selector, not from the configured topology.
    RATE=$(driver_cost_estimate "$BILLED_TYPE")
    COST=$(echo "scale=2; $ELAPSED_H * $RATE * ${BILLED_NODES:-1}" | bc 2>/dev/null || echo "?")
    log "Cluster was up for ${ELAPSED_H}h x ${BILLED_NODES:-?} node(s) @ EUR ${RATE}/node-h — estimated cost: EUR ${COST}"
    log "  (estimate only: excludes load balancers, volumes, snapshots, and egress overage)"
else
    log "No start time recorded — cannot estimate cost."
fi

# --- verify nothing remains ---
REMAINING=$(driver_server_count_by_label "$LABEL")
if [ "$REMAINING" -gt 0 ] 2>/dev/null; then
    fail "$REMAINING servers still exist with label $LABEL — manual cleanup needed"
    driver_show_servers_by_label "$LABEL"
    exit 1
fi

log "Teardown complete."
