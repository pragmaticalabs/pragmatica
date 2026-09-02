#!/usr/bin/env bash
# cloud-reaper.sh — Hetzner safety-net cleaner for Aether-labeled cloud resources.
#
# Lists every Hetzner Cloud resource carrying an `aether-cluster` OR `aether-node-id`
# label (servers, floating IPs, networks, firewalls, SSH keys) and — with --destroy —
# deletes them in dependency order.
#
# `aether-node-id` is included in the union so we catch ORPHANS from a failed bootstrap
# (where `aether-cluster` was never written) and CTM-provisioned VMs that may have
# inherited a wrong/missing cluster label. Pass --strict-cluster to limit to exact
# `aether-cluster` matches only.
#
# This is a SAFETY NET. It does NOT consult ~/.aether/clusters/<name>/bootstrap-state.json
# or any local state. It is 100% Hetzner-API-driven (label-match-only) so it
# works even when:
#   - bootstrap state is missing or corrupted
#   - teardown was interrupted mid-flight
#   - test process crashed leaving orphans
#   - manual debugging left ad-hoc resources

set -euo pipefail

# ---------------------------------------------------------------------------
# Colors / logging
# ---------------------------------------------------------------------------
if [ -t 1 ]; then
    RED=$'\033[0;31m'
    GREEN=$'\033[0;32m'
    YELLOW=$'\033[1;33m'
    BLUE=$'\033[0;34m'
    BOLD=$'\033[1m'
    NC=$'\033[0m'
else
    RED='' GREEN='' YELLOW='' BLUE='' BOLD='' NC=''
fi

log_info() { printf '%b[INFO]%b  %s\n'  "$GREEN"  "$NC" "$1"; }
log_warn() { printf '%b[WARN]%b  %s\n'  "$YELLOW" "$NC" "$1"; }
log_err()  { printf '%b[ERROR]%b %s\n'  "$RED"    "$NC" "$1" >&2; }
log_step() { printf '%b[STEP]%b  %s\n'  "$BLUE"   "$NC" "$1"; }
log_ok()   { printf '%b[ OK ]%b  %s\n'  "$GREEN"  "$NC" "$1"; }

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
usage() {
    cat <<'EOF'
cloud-reaper.sh — Hetzner safety-net cleaner for Aether-labeled cloud resources.

USAGE
    cloud-reaper.sh [--cluster <name>] [--destroy] [--force] [--help]

FLAGS
    --cluster <name>   Filter to resources labeled aether-cluster=<name>. Also includes
                       orphans (aether-node-id present but no aether-cluster) since they
                       commonly belong to <name> after a failed bootstrap.
                       Default: any non-empty aether-cluster OR aether-node-id label.
    --strict-cluster   Used with --cluster <name>: require exact aether-cluster=<name>
                       match. Excludes orphans even if they likely belong to the cluster.
    --exclude-cluster <name>
                       Never delete resources labeled aether-cluster=<name>. Repeatable.
                       Adds to the built-in protected set (see below).
    --allow-protected  Disable protected-cluster filtering. Required to delete the standing
                       shared infrastructure listed below. Use deliberately.
    --destroy          Actually delete resources (default is dry-run).
    --force            Skip the 5-second confirmation prompt (CI use).
    --help, -h         Print this help and exit.

PROTECTED BY DEFAULT
    test-pg            The standing PostgreSQL VM the cloud suites connect to. It is
                       aether-labeled but owned by NO integration run, so every catch-all
                       selector matches it. It was deleted this way on 2026-08-03 by a run
                       that failed before provisioning anything. Protection is enforced here
                       rather than at call sites because the call sites are what failed.

ENVIRONMENT
    HCLOUD_TOKEN       Required. Hetzner Cloud API token.

EXAMPLES
    cloud-reaper.sh                          # dry-run, list every aether-* resource
    cloud-reaper.sh --cluster aether-it      # dry-run, scope to one cluster
    cloud-reaper.sh --cluster aether-it --destroy
    cloud-reaper.sh --destroy --force        # CI: nuke all aether resources

EXIT CODES
    0  — clean / success
    1  — usage / setup error
    2  — one or more deletions failed (orphans remain)
EOF
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
CLUSTER=""
DESTROY=false
FORCE=false
STRICT_CLUSTER=false
# Long-lived SHARED infrastructure that this reaper must never delete by default.
#
# `test-pg` is the standing PostgreSQL VM the cloud suites connect to. It is aether-labeled
# (`aether-cluster=test-pg`) but is NOT owned by any integration run, so every catch-all
# selector matches it. On 2026-08-03 a cloud run that failed during bootstrap — before
# provisioning anything — reached run-tests.sh's bare safety-net reap and deleted both the PG
# VM and its firewall. Nothing in the tool prevented it; the only protection was the caller
# remembering to pass --cluster, which the safety-net deliberately does not.
#
# Protection lives HERE rather than at each call site because the call sites are exactly what
# cannot be relied on: the failure above happened in a code path whose own comment asserted
# "the integration run owns every aether-labeled resource in the account" — a premise that was
# simply false. Escape hatch: --allow-protected.
PROTECTED_CLUSTERS=("test-pg")
ALLOW_PROTECTED=false

while [ $# -gt 0 ]; do
    case "$1" in
        --cluster)
            [ $# -ge 2 ] || { log_err "--cluster requires a value"; exit 1; }
            CLUSTER="$2"
            shift 2
            ;;
        --cluster=*)
            CLUSTER="${1#*=}"
            shift
            ;;
        --strict-cluster) STRICT_CLUSTER=true; shift ;;
        --exclude-cluster) PROTECTED_CLUSTERS+=("$2"); shift 2 ;;
        --allow-protected) ALLOW_PROTECTED=true; shift ;;
        --destroy) DESTROY=true; shift ;;
        --force)   FORCE=true;   shift ;;
        --help|-h) usage; exit 0 ;;
        *)
            log_err "unknown argument: $1"
            usage >&2
            exit 1
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
for dep in curl jq; do
    if ! command -v "$dep" >/dev/null 2>&1; then
        log_err "missing dependency: $dep"
        exit 1
    fi
done

if [ -z "${HCLOUD_TOKEN:-}" ]; then
    log_err "HCLOUD_TOKEN is not set."
    log_err "Export your Hetzner Cloud API token before running this script:"
    log_err "    export HCLOUD_TOKEN=<your-token>"
    exit 1
fi

API="https://api.hetzner.cloud/v1"

# Build the list of label selectors to query. We always perform the union client-side
# (Hetzner label selectors are AND-only; OR is implemented by multiple queries + dedupe).
#
# Selection matrix:
#   --cluster X --strict-cluster  ->  ["aether-cluster=X"]              (exact match only)
#   --cluster X                    ->  ["aether-cluster=X", "aether-node-id"]
#                                       + post-filter: keep aether-node-id rows ONLY when
#                                         their aether-cluster label is empty/missing
#                                         (orphan capture, no cross-cluster bleed)
#   (no flag)                      ->  ["aether-cluster", "aether-node-id"]   (catch-all)
SELECTORS=()
if [ -n "$CLUSTER" ]; then
    SELECTORS+=("aether-cluster=${CLUSTER}")
    if [ "$STRICT_CLUSTER" != true ]; then
        SELECTORS+=("aether-node-id")
    fi
else
    SELECTORS+=("aether-cluster" "aether-node-id")
fi
SELECTOR_DESC=$(IFS=','; printf '%s' "${SELECTORS[*]}")

# ---------------------------------------------------------------------------
# HTTP wrappers
# ---------------------------------------------------------------------------
# hcloud_get <path-with-query>   — GET; prints body, exits non-zero on HTTP >= 400.
hcloud_get() {
    local path="$1"
    local tmp http
    tmp=$(mktemp)
    http=$(curl -sS -o "$tmp" -w '%{http_code}' \
            -H "Authorization: Bearer ${HCLOUD_TOKEN}" \
            "${API}${path}")
    if [ "$http" -ge 400 ]; then
        log_err "GET ${path} failed (HTTP ${http}):"
        cat "$tmp" >&2
        rm -f "$tmp"
        return 1
    fi
    cat "$tmp"
    rm -f "$tmp"
}

# hcloud_delete <path>   — DELETE; returns 0 on 2xx/204, non-zero otherwise.
hcloud_delete() {
    local path="$1"
    local tmp http
    tmp=$(mktemp)
    http=$(curl -sS -o "$tmp" -w '%{http_code}' -X DELETE \
            -H "Authorization: Bearer ${HCLOUD_TOKEN}" \
            "${API}${path}")
    if [ "$http" -ge 400 ]; then
        log_err "DELETE ${path} failed (HTTP ${http}):"
        cat "$tmp" >&2
        rm -f "$tmp"
        return 1
    fi
    rm -f "$tmp"
    return 0
}

# hcloud_post <path>   — POST {} (action endpoint), returns 0 on 2xx.
hcloud_post() {
    local path="$1"
    local tmp http
    tmp=$(mktemp)
    http=$(curl -sS -o "$tmp" -w '%{http_code}' -X POST \
            -H "Authorization: Bearer ${HCLOUD_TOKEN}" \
            -H "Content-Type: application/json" \
            -d '{}' \
            "${API}${path}")
    if [ "$http" -ge 400 ]; then
        log_err "POST ${path} failed (HTTP ${http}):"
        cat "$tmp" >&2
        rm -f "$tmp"
        return 1
    fi
    rm -f "$tmp"
    return 0
}

# ---------------------------------------------------------------------------
# Listing — returns "id<TAB>name<TAB>cluster_label" lines, one per resource.
# ---------------------------------------------------------------------------
# list_resource <plural-endpoint> <json-array-key>
#
# Queries Hetzner once per selector in $SELECTORS, unions the results client-side
# (deduped by id), and applies the orphan-capture post-filter when a non-strict
# `--cluster X` was given (rows surfaced only via aether-node-id are kept ONLY when
# their aether-cluster label is empty — never when it points to a different cluster).
list_resource() {
    local endpoint="$1" key="$2"
    local body sel enc_selector raw="" filtered
    for sel in "${SELECTORS[@]}"; do
        enc_selector=$(printf '%s' "$sel" | sed 's/=/%3D/g')
        if ! body=$(hcloud_get "/${endpoint}?label_selector=${enc_selector}&per_page=50"); then
            return 1
        fi
        raw+=$(printf '%s\n' "$body" | jq -r --arg key "$key" '
            .[$key][]?
            | [ (.id | tostring),
                (.name // ""),
                (.labels["aether-cluster"] // ""),
                (.labels["aether-node-id"] // "") ]
            | @tsv')
        raw+=$'\n'
    done

    # Orphan-capture post-filter: with --cluster X and no --strict-cluster, drop rows
    # that came in via aether-node-id but already have a different aether-cluster value.
    if [ -n "$CLUSTER" ] && [ "$STRICT_CLUSTER" != true ]; then
        filtered=$(printf '%s' "$raw" | awk -F'\t' -v c="$CLUSTER" '
            NF >= 4 && $1 != "" {
                if ($3 == "" || $3 == c) {print}
            }
            NF == 3 && $1 != "" {  # legacy 3-col rows shouldnt happen, but harmless
                if ($3 == "" || $3 == c) {print}
            }')
    else
        filtered="$raw"
    fi

    # Protected-cluster drop. Applied AFTER every selector mode converges here, so no mode —
    # including the catch-all no-flag one the run-tests.sh safety net uses — can bypass it.
    # `--allow-protected` is the deliberate escape hatch.
    if [ "$ALLOW_PROTECTED" != true ] && [ ${#PROTECTED_CLUSTERS[@]} -gt 0 ]; then
        protected_csv=$(IFS='|'; printf '%s' "${PROTECTED_CLUSTERS[*]}")
        filtered=$(printf '%s' "$filtered" | awk -F'\t' -v prot="$protected_csv" '
            BEGIN { n = split(prot, p, "|") }
            {
                for (i = 1; i <= n; i++) {
                    if ($3 == p[i]) { next }
                }
                print
            }')
    fi

    # Dedupe by id (col 1), strip trailing tab columns we no longer need.
    printf '%s' "$filtered" | awk -F'\t' '
        $1 != "" && !seen[$1]++ {
            printf "%s\t%s\t%s\n", $1, $2, $3
        }'
}

# ---------------------------------------------------------------------------
# Per-resource handlers
# ---------------------------------------------------------------------------
process_servers() {
    local lines
    lines=$(list_resource "servers" "servers") || return 1
    SERVER_LINES="$lines"
    [ -z "$lines" ] && { SERVER_COUNT=0; return 0; }
    SERVER_COUNT=$(printf '%s\n' "$lines" | wc -l | tr -d ' ')
}

process_floating_ips() {
    local lines
    lines=$(list_resource "floating_ips" "floating_ips") || return 1
    FIP_LINES="$lines"
    [ -z "$lines" ] && { FIP_COUNT=0; return 0; }
    FIP_COUNT=$(printf '%s\n' "$lines" | wc -l | tr -d ' ')
}

process_firewalls() {
    local lines
    lines=$(list_resource "firewalls" "firewalls") || return 1
    FW_LINES="$lines"
    [ -z "$lines" ] && { FW_COUNT=0; return 0; }
    FW_COUNT=$(printf '%s\n' "$lines" | wc -l | tr -d ' ')
}

process_networks() {
    local lines
    lines=$(list_resource "networks" "networks") || return 1
    NET_LINES="$lines"
    [ -z "$lines" ] && { NET_COUNT=0; return 0; }
    NET_COUNT=$(printf '%s\n' "$lines" | wc -l | tr -d ' ')
}

process_ssh_keys() {
    local lines
    lines=$(list_resource "ssh_keys" "ssh_keys") || return 1
    KEY_LINES="$lines"
    [ -z "$lines" ] && { KEY_COUNT=0; return 0; }
    KEY_COUNT=$(printf '%s\n' "$lines" | wc -l | tr -d ' ')
}

# ---------------------------------------------------------------------------
# Pretty printing
# ---------------------------------------------------------------------------
print_section() {
    local title="$1" lines="$2" count="$3"
    printf '%b%s%b (%d)\n' "$BOLD" "$title" "$NC" "$count"
    if [ "$count" -eq 0 ]; then
        printf '    (none)\n'
        return
    fi
    printf '    %-12s %-40s %s\n' "ID" "NAME" "CLUSTER"
    printf '    %-12s %-40s %s\n' "------------" "----------------------------------------" "-------"
    while IFS=$'\t' read -r id name cluster; do
        [ -z "$id" ] && continue
        printf '    %-12s %-40s %s\n' "$id" "$name" "$cluster"
    done <<< "$lines"
}

print_inventory() {
    local label="$1"
    echo
    printf '%b===== Hetzner Aether resources — %s =====%b\n' "$BOLD" "$label" "$NC"
    print_section "Servers"      "$SERVER_LINES" "$SERVER_COUNT"
    print_section "Floating IPs" "$FIP_LINES"    "$FIP_COUNT"
    print_section "Firewalls"    "$FW_LINES"     "$FW_COUNT"
    print_section "Networks"     "$NET_LINES"    "$NET_COUNT"
    print_section "SSH Keys"     "$KEY_LINES"    "$KEY_COUNT"
    echo
    printf '  TOTAL: servers=%d  floating_ips=%d  firewalls=%d  networks=%d  ssh_keys=%d\n' \
        "$SERVER_COUNT" "$FIP_COUNT" "$FW_COUNT" "$NET_COUNT" "$KEY_COUNT"
}

# ---------------------------------------------------------------------------
# Inventory all resources
# ---------------------------------------------------------------------------
SERVER_LINES="" FIP_LINES="" FW_LINES="" NET_LINES="" KEY_LINES=""
SERVER_COUNT=0 FIP_COUNT=0 FW_COUNT=0 NET_COUNT=0 KEY_COUNT=0

inventory() {
    SERVER_LINES="" FIP_LINES="" FW_LINES="" NET_LINES="" KEY_LINES=""
    SERVER_COUNT=0 FIP_COUNT=0 FW_COUNT=0 NET_COUNT=0 KEY_COUNT=0
    process_servers
    process_floating_ips
    process_firewalls
    process_networks
    process_ssh_keys
}

# ---------------------------------------------------------------------------
# Destroy paths
# ---------------------------------------------------------------------------
FAILURES=0

destroy_server() {
    local id="$1" name="$2"
    log_step "delete server ${id} (${name})"
    if hcloud_delete "/servers/${id}"; then
        log_ok "server ${id} deleted"
    else
        log_err "server ${id} delete failed"
        FAILURES=$((FAILURES + 1))
    fi
}

# Floating IPs require unassigning before deletion if attached to a server.
destroy_floating_ip() {
    local id="$1" name="$2"
    local body server_id
    if body=$(hcloud_get "/floating_ips/${id}"); then
        server_id=$(printf '%s\n' "$body" | jq -r '.floating_ip.server // empty')
    else
        server_id=""
    fi

    if [ -n "$server_id" ]; then
        log_step "unassign floating_ip ${id} (${name}) from server ${server_id}"
        if ! hcloud_post "/floating_ips/${id}/actions/unassign"; then
            log_err "floating_ip ${id} unassign failed"
            FAILURES=$((FAILURES + 1))
            return
        fi
    fi

    log_step "delete floating_ip ${id} (${name})"
    if hcloud_delete "/floating_ips/${id}"; then
        log_ok "floating_ip ${id} deleted"
    else
        log_err "floating_ip ${id} delete failed"
        FAILURES=$((FAILURES + 1))
    fi
}

# Firewalls must be detached from all resources before deletion.
destroy_firewall() {
    local id="$1" name="$2"
    local body applied tmp http
    if body=$(hcloud_get "/firewalls/${id}"); then
        applied=$(printf '%s\n' "$body" \
            | jq -c '[ .firewall.applied_to[]? | {type, server: (.server.id // null), label_selector: (.label_selector.selector // null)} ]')
    else
        applied="[]"
    fi

    if [ "$applied" != "[]" ]; then
        log_step "detach firewall ${id} (${name}) from $(printf '%s' "$applied" | jq 'length') resource(s)"
        # Build a remove_from_resources payload mirroring the applied_to array.
        local payload
        payload=$(printf '%s' "$applied" | jq '{remove_from: [
            .[] | if .type == "server"
                  then {type:"server", server:{id: .server}}
                  else {type:"label_selector", label_selector:{selector: .label_selector}}
                  end
        ]}')
        tmp=$(mktemp)
        http=$(curl -sS -o "$tmp" -w '%{http_code}' -X POST \
                -H "Authorization: Bearer ${HCLOUD_TOKEN}" \
                -H "Content-Type: application/json" \
                -d "$payload" \
                "${API}/firewalls/${id}/actions/remove_from_resources")
        if [ "$http" -ge 400 ]; then
            log_err "firewall ${id} detach failed (HTTP ${http}):"
            cat "$tmp" >&2
            rm -f "$tmp"
            FAILURES=$((FAILURES + 1))
            return
        fi
        rm -f "$tmp"
    fi

    log_step "delete firewall ${id} (${name})"
    if hcloud_delete "/firewalls/${id}"; then
        log_ok "firewall ${id} deleted"
    else
        log_err "firewall ${id} delete failed"
        FAILURES=$((FAILURES + 1))
    fi
}

destroy_network() {
    local id="$1" name="$2"
    log_step "delete network ${id} (${name})"
    if hcloud_delete "/networks/${id}"; then
        log_ok "network ${id} deleted"
    else
        log_err "network ${id} delete failed"
        FAILURES=$((FAILURES + 1))
    fi
}

destroy_ssh_key() {
    local id="$1" name="$2"
    log_step "delete ssh_key ${id} (${name})"
    if hcloud_delete "/ssh_keys/${id}"; then
        log_ok "ssh_key ${id} deleted"
    else
        log_err "ssh_key ${id} delete failed"
        FAILURES=$((FAILURES + 1))
    fi
}

iterate_lines() {
    local lines="$1" handler="$2"
    [ -z "$lines" ] && return 0
    while IFS=$'\t' read -r id name _cluster; do
        [ -z "$id" ] && continue
        "$handler" "$id" "$name"
    done <<< "$lines"
}

# ---------------------------------------------------------------------------
# Main flow
# ---------------------------------------------------------------------------
log_info "selectors: ${SELECTOR_DESC}"
if [ "$ALLOW_PROTECTED" = true ]; then
    log_warn "protected-cluster filtering DISABLED (--allow-protected) — shared infrastructure IS deletable"
else
    log_info "protected (never deleted): $(IFS=','; printf '%s' "${PROTECTED_CLUSTERS[*]}")"
fi
log_info "endpoint: ${API}"
[ "$DESTROY" = true ] && log_warn "MODE: DESTROY (resources WILL be deleted)" \
                      || log_info "MODE: dry-run (no deletions)"

inventory
print_inventory "current state"

TOTAL=$((SERVER_COUNT + FIP_COUNT + FW_COUNT + NET_COUNT + KEY_COUNT))

if [ "$DESTROY" = false ]; then
    echo
    if [ "$TOTAL" -eq 0 ]; then
        log_ok "no aether-labeled resources found — account is CLEAN"
    else
        log_info "dry-run complete — pass --destroy to actually delete the ${TOTAL} resource(s) above"
    fi
    exit 0
fi

if [ "$TOTAL" -eq 0 ]; then
    echo
    log_ok "nothing to destroy — already clean"
    exit 0
fi

# ---------- destruction confirmation ----------
echo
log_warn "About to DELETE ${TOTAL} Hetzner resource(s) listed above."
log_warn "Selectors: ${SELECTOR_DESC}"
if [ "$FORCE" = false ]; then
    log_warn "Press Ctrl-C within 5 seconds to abort..."
    for i in 5 4 3 2 1; do
        printf '%b  %ds...%b\n' "$YELLOW" "$i" "$NC"
        sleep 1
    done
fi

echo
log_step "destruction phase"

# Order matters: servers → floating IPs → firewalls → networks → SSH keys.
# (Servers may pin floating IPs, firewalls, and networks. SSH keys are leaves.)
iterate_lines "$SERVER_LINES" destroy_server
iterate_lines "$FIP_LINES"    destroy_floating_ip
iterate_lines "$FW_LINES"     destroy_firewall
iterate_lines "$NET_LINES"    destroy_network
iterate_lines "$KEY_LINES"    destroy_ssh_key

# ---------- post-destruction verification ----------
echo
log_step "post-destruction verification"
inventory
print_inventory "after destroy"

REMAINING=$((SERVER_COUNT + FIP_COUNT + FW_COUNT + NET_COUNT + KEY_COUNT))

echo
if [ "$FAILURES" -gt 0 ] || [ "$REMAINING" -gt 0 ]; then
    log_err "incomplete cleanup: ${FAILURES} failure(s), ${REMAINING} resource(s) still present"
    exit 2
fi

log_ok "all aether-labeled resources destroyed (${TOTAL} total) — account is CLEAN"
exit 0
