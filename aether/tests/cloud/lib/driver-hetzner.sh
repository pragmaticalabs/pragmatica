#!/bin/bash
# driver-hetzner.sh — Hetzner Cloud implementation of the cloud-driver contract.
#
# RFC-0016 §5 W7 (part 1). This file is the ONLY place `hcloud` is invoked in
# aether/tests/cloud/ — the outer scripts (deploy-cloud.sh / teardown-cloud.sh)
# call the driver_<op> functions below and never touch hcloud directly.
#
# BEHAVIOR-PRESERVING EXTRACTION: every function body is the exact hcloud command
# (and, where the value is derived from hcloud JSON, the exact `python3` parse) that
# previously lived inline in the caller. Only the command tokens moved here; all
# control flow, logging, `|| true` tolerance, guards and loops stayed in the caller.
# Sourced (not executed) so the functions run in the caller's shell under its
# `set -euo pipefail` — pipefail therefore still governs the piped reads below,
# identical to the pre-extraction inline form.
#
# Contract note: the RFC's high-level ops driver_managed_lb and driver_reap_by_label
# are each realized here as a FAMILY of thin per-command functions (driver_lb_* /
# driver_reap_* + driver_*_by_label), so the callers keep their exact per-step logging
# and loops. See lib/cloud-driver.sh for the op→function mapping.

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
# Provider CLI presence. (HCLOUD_TOKEN credential presence stays an env-var-name
# check in the callers — see cloud-driver.sh grep-exception note.)
driver_require_cli() { command -v hcloud >/dev/null 2>&1; }

# ---------------------------------------------------------------------------
# RFC op: driver_cost_estimate — per-hour EUR rate for ONE server of a given type.
#
#   $1 = instance type (e.g. cx23). Optional; omitted falls back to the conservative
#        default below.
#
# Previously this was a single literal (0.071) independent of instance type AND of fleet
# size, so a 100-node run reported the cost of one node — wrong by exactly N. The caller
# is responsible for multiplying by the node count (see teardown-cloud.sh); this op
# answers only "what does one of these cost per hour".
#
# Rates are approximate, EUR, and dated: Hetzner raised cloud prices in June 2026 (up to
# 3.1x on some lines) and the CX22 line was superseded by CX23. Derived from monthly list
# prices / 730. US locations run ~20% higher and include far less traffic — treat these as
# an EU-location floor, not a quote.
#
# UNKNOWN TYPES DELIBERATELY FALL BACK HIGH AND LOUD. The defect being fixed here is
# under-reporting cost; a silent low default would reintroduce it in a new form.
driver_cost_estimate() {
    local instance_type="${1:-}"
    case "$instance_type" in
        cx22)  echo "0.0060" ;;   # legacy/deprecated (superseded by cx23)
        cx23)  echo "0.0075" ;;
        cx33)  echo "0.0150" ;;
        cax11) echo "0.0062" ;;   # ARM (Ampere), DE/FI only
        cax21) echo "0.0120" ;;
        cpx11) echo "0.0070" ;;
        cpx21) echo "0.0130" ;;
        cpx31) echo "0.0250" ;;
        cpx32) echo "0.0280" ;;
        ccx13) echo "0.0180" ;;   # dedicated vCPU
        ccx23) echo "0.0336" ;;
        ccx33) echo "0.0672" ;;
        ccx63) echo "1.3678" ;;
        "")
            echo "0.0710" ;;      # no type supplied — conservative default (the historical literal)
        *)
            echo "driver_cost_estimate: unknown instance type '${instance_type}' — using conservative 0.0710/h; the estimate may OVERSTATE cost. Add the type to the table in lib/driver-hetzner.sh." >&2
            echo "0.0710" ;;
    esac
}

# ---------------------------------------------------------------------------
# RFC op: driver_provider_toml — the Hetzner-source bootstrap TOML.
#   $1 = config dir (caller's SCRIPT_DIR).
# ---------------------------------------------------------------------------
driver_provider_toml() { echo "${1}/aether-cloud.toml"; }

# ---------------------------------------------------------------------------
# RFC op: driver_create_sshkey
# ---------------------------------------------------------------------------
driver_sshkey_exists() { hcloud ssh-key describe "$1" >/dev/null 2>&1; }
driver_create_sshkey() { hcloud ssh-key create --name "$1" --public-key-from-file "$2" >/dev/null; }

# ---------------------------------------------------------------------------
# RFC op: driver_init_network
#   driver_init_network <name> <ip-range> <label>  — create + add-subnet
#   driver_network_id   <name>                     — numeric network id (JSON)
# ---------------------------------------------------------------------------
driver_network_exists() { hcloud network describe "$1" >/dev/null 2>&1; }
driver_init_network() {
    hcloud network create --name "$1" --ip-range "$2" \
        --label "$3" >/dev/null
    hcloud network add-subnet "$1" --type cloud \
        --network-zone eu-central --ip-range "$2" >/dev/null
}
driver_network_id() { hcloud network describe "$1" -o json | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])'; }

# ---------------------------------------------------------------------------
# RFC op: driver_create_support_vm (PG + LB VMs, and the core-node existence guard)
#   driver_create_support_vm <name> <type> <image> <location> <sshkey> <network> <label> <role-label>
# ---------------------------------------------------------------------------
driver_core_node_count() { hcloud server list --selector "${1},aether-role=core" -o noheader 2>/dev/null | wc -l | tr -d ' '; }
driver_node_exists() { hcloud server describe "$1" >/dev/null 2>&1; }
driver_create_support_vm() {
    hcloud server create \
        --name "$1" \
        --type "$2" \
        --image "$3" \
        --location "$4" \
        --ssh-key "$5" \
        --network "$6" \
        --label "$7" \
        --label "$8" >/dev/null
}

# ---------------------------------------------------------------------------
# RFC op: driver_node_public_ip — public IPv4 of a server (JSON).
# ---------------------------------------------------------------------------
driver_node_public_ip() { hcloud server describe "$1" -o json | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["public_net"]["ipv4"]["ip"])'; }

# ---------------------------------------------------------------------------
# RFC op: driver_managed_lb (family) — Hetzner managed load balancer.
#   driver_lb_add_service  <name> <protocol> <listen-port> <destination-port>
#   driver_lb_health_check <name> <port> <http-path> <interval> <timeout> <retries>
#   driver_lb_add_target   <name> <server>   (private-ip target; best-effort at caller)
# ---------------------------------------------------------------------------
driver_lb_exists() { hcloud load-balancer describe "$1" >/dev/null 2>&1; }
driver_lb_create() { hcloud load-balancer create --name "$1" --type "$2" --location "$3" --label "$4" >/dev/null; }
driver_lb_attach_network() { hcloud load-balancer attach-to-network "$1" --network "$2" >/dev/null; }
driver_lb_add_service() { hcloud load-balancer add-service "$1" --protocol "$2" --listen-port "$3" --destination-port "$4" >/dev/null; }
driver_lb_health_check() {
    hcloud load-balancer update-health-check "$1" \
        --protocol http --port "$2" --http-path "$3" \
        --interval "$4" --timeout "$5" --retries "$6" >/dev/null 2>&1
}
driver_lb_add_target() { hcloud load-balancer add-target "$1" --server "$2" --use-private-ip >/dev/null 2>&1; }
driver_lb_public_ip() { hcloud load-balancer describe "$1" -o json | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["public_net"]["ipv4"]["ip"])'; }

# ---------------------------------------------------------------------------
# RFC op: driver_reap_by_label (family) — teardown enumeration + deletes.
# Selector-scoped list/delete per resource, the aether-core name-sweep, and the
# final verification list. Callers keep their per-resource logging and `|| true`.
# ---------------------------------------------------------------------------
driver_reap_list_servers()  { hcloud server list --selector "$1" -o columns=id,name,status; }
driver_reap_list_lbs()      { hcloud load-balancer list --selector "$1" -o columns=id,name; }
driver_reap_list_networks() { hcloud network list --selector "$1" -o columns=id,name; }
driver_reap_list_sshkeys()  { hcloud ssh-key list -o columns=id,name; }

driver_lb_ids_by_label()      { hcloud load-balancer list --selector "$1" -o noheader -o columns=id 2>/dev/null; }
driver_delete_lb()            { hcloud load-balancer delete "$1"; }
driver_server_ids_by_label()  { hcloud server list --selector "$1" -o noheader -o columns=id 2>/dev/null; }
driver_delete_server()        { hcloud server delete "$1"; }
driver_orphan_core_server_ids() { hcloud server list -o noheader -o columns=id,name 2>/dev/null | grep "aether-core" | awk '{print $1}'; }
driver_network_ids_by_label() { hcloud network list --selector "$1" -o noheader -o columns=id 2>/dev/null; }
driver_delete_network()       { hcloud network delete "$1"; }
driver_delete_sshkey()        { hcloud ssh-key delete "$1"; }
driver_server_count_by_label(){ hcloud server list --selector "$1" -o noheader 2>/dev/null | wc -l | tr -d ' '; }
# Dominant server type behind a label — the cost summary needs a type, not just a count.
# Reports the most common type so a fleet with a stray odd node still prices sensibly.
driver_server_type_by_label(){ hcloud server list --selector "$1" -o noheader -o columns=type 2>/dev/null | sort | uniq -c | sort -rn | head -1 | awk '{print $2}'; }
driver_show_servers_by_label(){ hcloud server list --selector "$1"; }
