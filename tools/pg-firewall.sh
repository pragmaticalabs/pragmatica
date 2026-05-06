#!/usr/bin/env bash
# pg-firewall.sh — Manage the Hetzner firewall guarding the integration-test PG VM.
#
# Default state: 22/tcp from operator IP only. PostgreSQL (5432/tcp) is denied.
# `open`  adds 5432/tcp from 0.0.0.0/0 to the rule set (run before tests).
# `close` removes the 5432 rule (run after tests / on teardown).
#
# The firewall is identified by name (`aether-pg-firewall` by default) and applied
# to the PG VM resolved via the same label selector as provision-test-pg.sh
# (`aether-cluster=test-pg`).
#
# Subcommands:
#   init     One-time: create firewall + apply to PG VM. Re-runnable to refresh
#            operator IP after roaming networks.
#   open     Set rules to baseline + 5432/tcp open (during test window).
#   close    Reset rules to baseline (5432 denied).
#   status   Show current rules and applied-to.
#   destroy  Detach + delete the firewall.
#
# Env:
#   HCLOUD_TOKEN     Required. Hetzner Cloud API token.
#   FIREWALL_NAME    Override firewall name (default: aether-pg-firewall).
#   PG_LABEL         Override PG VM label selector (default: aether-cluster=test-pg).
#   OPERATOR_IPS     Override operator-IP detection. Comma-separated CIDRs.
#                    Default: auto-detected via https://ifconfig.me + /32.
set -euo pipefail

if [ -t 1 ]; then
    RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[1;33m'; BLUE=$'\033[0;34m'; NC=$'\033[0m'
else
    RED=''; GREEN=''; YELLOW=''; BLUE=''; NC=''
fi
log_info() { printf '%b[INFO]%b  %s\n'  "$GREEN"  "$NC" "$1" >&2; }
log_warn() { printf '%b[WARN]%b  %s\n'  "$YELLOW" "$NC" "$1" >&2; }
log_err()  { printf '%b[ERROR]%b %s\n'  "$RED"    "$NC" "$1" >&2; }
log_step() { printf '%b[STEP]%b  %s\n'  "$BLUE"   "$NC" "$1" >&2; }
log_ok()   { printf '%b[ OK ]%b  %s\n'  "$GREEN"  "$NC" "$1" >&2; }

: "${HCLOUD_TOKEN:?HCLOUD_TOKEN is not set}"
FIREWALL_NAME="${FIREWALL_NAME:-aether-pg-firewall}"
PG_LABEL="${PG_LABEL:-aether-cluster=test-pg}"

API="https://api.hetzner.cloud/v1"

hcloud_get() {
    local path="$1"
    curl -sS --max-time 30 -H "Authorization: Bearer ${HCLOUD_TOKEN}" "${API}${path}"
}

hcloud_post() {
    local path="$1" body="${2:-}"
    if [ -n "$body" ]; then
        curl -sS --max-time 30 -X POST -H "Authorization: Bearer ${HCLOUD_TOKEN}" \
            -H "Content-Type: application/json" -d "$body" "${API}${path}"
    else
        curl -sS --max-time 30 -X POST -H "Authorization: Bearer ${HCLOUD_TOKEN}" "${API}${path}"
    fi
}

hcloud_delete() {
    curl -sS --max-time 30 -X DELETE -H "Authorization: Bearer ${HCLOUD_TOKEN}" "${API}$1"
}

encode_selector() {
    printf '%s' "$1" | sed 's/=/%3D/g'
}

resolve_pg_server_id() {
    local enc body
    enc=$(encode_selector "$PG_LABEL")
    body=$(hcloud_get "/servers?label_selector=${enc}&per_page=50")
    local id
    id=$(printf '%s' "$body" | jq -r '.servers[0].id // empty')
    [ -z "$id" ] && { log_err "no server matched label '${PG_LABEL}'"; return 1; }
    printf '%s' "$id"
}

resolve_firewall_id() {
    hcloud_get "/firewalls?name=${FIREWALL_NAME}" | jq -r '.firewalls[0].id // empty'
}

detect_operator_ips() {
    if [ -n "${OPERATOR_IPS:-}" ]; then
        printf '%s' "$OPERATOR_IPS"
        return 0
    fi
    local ip
    ip=$(curl -sS --max-time 10 https://ifconfig.me 2>/dev/null) || {
        log_err "could not detect operator IP via ifconfig.me; set OPERATOR_IPS=<cidr>[,<cidr>...]"
        return 2
    }
    printf '%s/32' "$ip"
}

# Build a rules JSON array. Args:
#   $1  operator-IPs CSV (CIDRs)
#   $2  "open" to include 5432, anything else for baseline
build_rules_json() {
    local op_ips="$1" mode="${2:-baseline}"
    local op_sources
    op_sources=$(printf '%s' "$op_ips" | tr ',' '\n' | jq -R . | jq -cs .)
    local ssh_rule
    ssh_rule=$(jq -nc --argjson s "$op_sources" \
        '{direction:"in", protocol:"tcp", port:"22", source_ips:$s, description:"operator SSH"}')
    if [ "$mode" = "open" ]; then
        local pg_rule
        pg_rule=$(jq -nc \
            '{direction:"in", protocol:"tcp", port:"5432", source_ips:["0.0.0.0/0","::/0"], description:"Postgres (test window)"}')
        jq -nc --argjson s "$ssh_rule" --argjson p "$pg_rule" '[$s, $p]'
    else
        jq -nc --argjson s "$ssh_rule" '[$s]'
    fi
}

set_rules() {
    local fw_id="$1" rules="$2" body status
    body=$(printf '{"rules": %s}' "$rules")
    status=$(hcloud_post "/firewalls/${fw_id}/actions/set_rules" "$body" \
        | jq -r '.actions[0].status // "error"')
    [ "$status" = "running" ] || [ "$status" = "success" ]
}

cmd_init() {
    local op_ips server_id fw_id rules
    op_ips=$(detect_operator_ips)
    log_info "Operator IPs: ${op_ips}"
    server_id=$(resolve_pg_server_id)
    log_info "PG server id: ${server_id}"
    rules=$(build_rules_json "$op_ips" baseline)
    fw_id=$(resolve_firewall_id)
    if [ -z "$fw_id" ]; then
        log_step "Creating firewall '${FIREWALL_NAME}'"
        local apply_to payload
        apply_to=$(jq -nc --argjson id "$server_id" \
            '[{type:"server", server:{id:$id}}]')
        payload=$(jq -nc --arg n "$FIREWALL_NAME" --argjson r "$rules" --argjson at "$apply_to" \
            '{name:$n, rules:$r, apply_to:$at, labels:{"aether-cluster":"test-pg"}}')
        local resp
        resp=$(hcloud_post "/firewalls" "$payload")
        fw_id=$(printf '%s' "$resp" | jq -r '.firewall.id // empty')
        [ -z "$fw_id" ] && { log_err "create failed: $(printf '%s' "$resp" | jq -c '.error // .')"; exit 2; }
        log_ok "created firewall id=${fw_id}, applied to PG VM ${server_id}"
    else
        log_step "Resetting baseline rules for existing firewall id=${fw_id}"
        set_rules "$fw_id" "$rules" || { log_err "set_rules failed"; exit 2; }
        local apply_to status
        apply_to=$(jq -nc --argjson id "$server_id" \
            '{apply_to:[{type:"server", server:{id:$id}}]}')
        status=$(hcloud_post "/firewalls/${fw_id}/actions/apply_to_resources" "$apply_to" \
            | jq -r '.actions[0].status // "error"')
        case "$status" in
            running|success) log_ok "applied to PG VM ${server_id}" ;;
            *) log_warn "apply_to_resources returned status=${status} (may already be applied)" ;;
        esac
    fi
}

cmd_open() {
    local fw_id op_ips rules
    fw_id=$(resolve_firewall_id)
    [ -z "$fw_id" ] && { log_err "firewall '${FIREWALL_NAME}' not found — run 'init' first"; exit 2; }
    op_ips=$(detect_operator_ips)
    rules=$(build_rules_json "$op_ips" open)
    set_rules "$fw_id" "$rules" || { log_err "set_rules failed"; exit 2; }
    log_ok "PG firewall: 5432 OPEN to 0.0.0.0/0 (during test window)"
}

cmd_close() {
    local fw_id op_ips rules
    fw_id=$(resolve_firewall_id) || true
    [ -z "$fw_id" ] && { log_warn "firewall '${FIREWALL_NAME}' not found — nothing to close"; return 0; }
    op_ips=$(detect_operator_ips)
    rules=$(build_rules_json "$op_ips" baseline)
    set_rules "$fw_id" "$rules" || { log_err "set_rules failed"; exit 2; }
    log_ok "PG firewall: 5432 CLOSED (baseline restored)"
}

cmd_status() {
    local fw_id
    fw_id=$(resolve_firewall_id) || true
    [ -z "$fw_id" ] && { log_warn "no firewall named '${FIREWALL_NAME}'"; return 0; }
    hcloud_get "/firewalls/${fw_id}" \
        | jq '{name: .firewall.name, applied_to: [.firewall.applied_to[] | {type, server_id: .server.id}], rules: .firewall.rules}'
}

cmd_destroy() {
    local fw_id server_id
    fw_id=$(resolve_firewall_id) || true
    [ -z "$fw_id" ] && { log_warn "no firewall named '${FIREWALL_NAME}' to destroy"; return 0; }
    server_id=$(resolve_pg_server_id) || true
    if [ -n "$server_id" ]; then
        local payload
        payload=$(jq -nc --argjson id "$server_id" \
            '{remove_from:[{type:"server", server:{id:$id}}]}')
        hcloud_post "/firewalls/${fw_id}/actions/remove_from_resources" "$payload" >/dev/null || true
    fi
    hcloud_delete "/firewalls/${fw_id}" >/dev/null
    log_ok "destroyed firewall '${FIREWALL_NAME}' (id=${fw_id})"
}

main() {
    case "${1:-}" in
        init)    cmd_init ;;
        open)    cmd_open ;;
        close)   cmd_close ;;
        status)  cmd_status ;;
        destroy) cmd_destroy ;;
        ""|-h|--help)
            sed -n '2,/^set -euo/p' "$0" | sed 's/^# //;s/^#$//;/^set -euo/d'
            ;;
        *) log_err "unknown subcommand: $1"; exit 1 ;;
    esac
}

main "$@"
