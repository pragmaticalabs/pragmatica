#!/bin/bash
# cloud-driver.sh — provider-driver dispatcher for the cloud e2e harness.
#
# RFC-0016 §5 (W7). Selects a provider implementation by AETHER_CLOUD_PROVIDER
# (default: hetzner) and sources it, exposing a stable driver_<op> contract to
# deploy-cloud.sh / teardown-cloud.sh. Source this file (it runs in the caller's
# shell); it in turn sources lib/driver-<provider>.sh from this directory.
#
# ===========================================================================
# CONTRACT — RFC §5 op list → concrete driver functions (Hetzner, part 1)
# ===========================================================================
#   driver_create_sshkey       driver_sshkey_exists / driver_create_sshkey
#   driver_init_network        driver_network_exists / driver_init_network /
#                              driver_network_id
#   driver_provider_toml       driver_provider_toml
#   driver_create_support_vm   driver_node_exists / driver_create_support_vm /
#                              driver_core_node_count
#   driver_node_public_ip      driver_node_public_ip
#   driver_managed_lb          driver_lb_exists / driver_lb_create /
#                              driver_lb_attach_network / driver_lb_add_service /
#                              driver_lb_health_check / driver_lb_add_target /
#                              driver_lb_public_ip
#   driver_reap_by_label       driver_reap_list_* / driver_*_ids_by_label /
#                              driver_delete_* / driver_orphan_core_server_ids /
#                              driver_server_count_by_label / driver_show_servers_by_label
#   driver_cost_estimate       driver_cost_estimate [instance_type]
#                              Returns the per-hour rate for ONE server of that type.
#                              Callers MUST multiply by the fleet size themselves — the
#                              op deliberately does not know the node count, and omitting
#                              that factor understates a run by exactly N (fixed 2026-08-12).
#                              An unknown type must fall back HIGH and warn, never low.
#   (preflight)                driver_require_cli
#
# All eight RFC §5 ops ARE implemented by the Hetzner driver (part 1); there are
# no loud-fail op stubs — the only loud-fail is provider selection below, for a
# provider whose lib/driver-<name>.sh does not exist yet (e.g. aws → part 2).
#
# ===========================================================================
# PART 2 — NOT YET EXTRACTED (documented so this contract does not imply coverage)
# ===========================================================================
#  1. AWS driver (lib/driver-aws.sh): VPC init, PG-on-EC2 support VM, ELBv2
#     target-group/listener, tag-filter reaper — RFC §5 AWS column, deferred
#     until AWS creds / W5 LocalStack land.
#  2. The run-tests.sh --env cloud path (aether/tests/integration/lib/{common,cluster}.sh)
#     is a SEPARATE hcloud surface NOT covered by this driver. Its provider ops,
#     to be folded in later, are:
#       - reap-by-selector enumeration + delete (server list/delete by label)
#       - power on/off "revive" (server poweron / poweroff)
#       - PG-firewall management (firewall create / replace-rules / apply-to-resource
#         / remove-from-resource / delete / describe)
#     These remain inline in the integration harness and are out of W7-part-1 scope.

_cloud_driver_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AETHER_CLOUD_PROVIDER="${AETHER_CLOUD_PROVIDER:-hetzner}"
_cloud_driver_impl="${_cloud_driver_dir}/driver-${AETHER_CLOUD_PROVIDER}.sh"

if [ ! -f "$_cloud_driver_impl" ]; then
    echo "[cloud-driver] ERROR: no driver for AETHER_CLOUD_PROVIDER='${AETHER_CLOUD_PROVIDER}'" >&2
    echo "[cloud-driver]        expected implementation at ${_cloud_driver_impl}" >&2
    echo "[cloud-driver]        implemented providers: hetzner (part 1). aws/gcp/azure are part 2 (RFC-0016 §5)." >&2
    exit 1
fi

# shellcheck source=/dev/null
source "$_cloud_driver_impl"
