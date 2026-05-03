#!/usr/bin/env bash
# provision-test-pg.sh — Provision a single Hetzner VM running PostgreSQL for cloud
# integration tests, and emit `export PG_URL=...` to stdout + /tmp/aether-test-pg.env.
#
# IDEMPOTENT: re-running with no flags reuses an existing PG VM (label
# `aether-cluster=test-pg`). Use --fresh to force re-provisioning, --destroy to
# tear down, --print-only to query without provisioning.
#
# Same design philosophy as cloud-reaper.sh: pure Hetzner-API + ssh, no
# dependency on `aether` CLI or local bootstrap state.

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
provision-test-pg.sh — Provision a Hetzner PostgreSQL VM for cloud integration tests.

USAGE
    provision-test-pg.sh [--fresh | --destroy | --print-only] [--help]

FLAGS
    (no flag)        Reuse existing PG VM if present, else provision a fresh one.
    --fresh          Destroy any existing PG VM first, then provision new.
    --destroy        Destroy the PG VM and its SSH key, then exit.
    --print-only     Print existing PG_URL if VM exists; exit 1 if not provisioned.
    --help, -h       Print this help and exit.

ENVIRONMENT
    HCLOUD_TOKEN     Required. Hetzner Cloud API token.
    AETHER_SSH_KEY   Required. Path to SSH private key. Public key derived from
                     ${AETHER_SSH_KEY}.pub or via `ssh-keygen -y -f`.
    AETHER_SSH_USER  Optional. SSH user for VM access (default: root).
    PG_VM_TYPE       Optional. Hetzner server type (default: cx23).
    PG_VM_LOCATION   Optional. Hetzner location (default: fsn1).
    PG_VM_IMAGE      Optional. Hetzner image (default: ubuntu-22.04).

OUTPUT
    On success, prints to stdout and to /tmp/aether-test-pg.env (mode 0600):
        # PG VM <id>  IP=<ip>  provisioned=<timestamp>
        export PG_URL=postgres://aether:<password>@<ip>:5432/aether_forge

    Tests can pick this up via `source /tmp/aether-test-pg.env`.

EXAMPLES
    provision-test-pg.sh                    # idempotent provision/reuse
    provision-test-pg.sh --fresh            # force re-deploy
    provision-test-pg.sh --print-only       # CI: assume already provisioned
    provision-test-pg.sh --destroy          # tear down

EXIT CODES
    0  — success (PG VM ready, env file written)
    1  — usage / setup error / --print-only with no VM
    2  — Hetzner API failure / provisioning failure
EOF
}

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
readonly API="https://api.hetzner.cloud/v1"
readonly CLUSTER_LABEL="test-pg"
readonly ROLE_LABEL="postgres"
readonly SELECTOR="aether-cluster=${CLUSTER_LABEL}"
readonly ENV_FILE="/tmp/aether-test-pg.env"
readonly VM_NAME_PREFIX="aether-test-pg"
readonly SSH_KEY_NAME="aether-test-pg-key"

# Defaults overridable via env
PG_VM_TYPE="${PG_VM_TYPE:-cx23}"
PG_VM_LOCATION="${PG_VM_LOCATION:-fsn1}"
PG_VM_IMAGE="${PG_VM_IMAGE:-ubuntu-22.04}"
AETHER_SSH_USER="${AETHER_SSH_USER:-root}"

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
MODE="reuse"  # reuse | fresh | destroy | print-only

while [ $# -gt 0 ]; do
    case "$1" in
        --fresh)      MODE="fresh";      shift ;;
        --destroy)    MODE="destroy";    shift ;;
        --print-only) MODE="print-only"; shift ;;
        --help|-h)    usage; exit 0 ;;
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
for dep in curl jq openssl ssh-keygen ssh; do
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

# AETHER_SSH_KEY is needed for everything except --print-only and --destroy
# (destroy may still want it for the SSH key cleanup, but not strictly required).
if [ "$MODE" != "print-only" ] && [ -z "${AETHER_SSH_KEY:-}" ]; then
    log_err "AETHER_SSH_KEY is not set."
    log_err "Export the path to your SSH private key:"
    log_err "    export AETHER_SSH_KEY=~/.ssh/aether_test"
    exit 1
fi

# ---------------------------------------------------------------------------
# HTTP wrappers (mirroring cloud-reaper.sh style)
# ---------------------------------------------------------------------------
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
        return 2
    fi
    cat "$tmp"
    rm -f "$tmp"
}

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
        return 2
    fi
    rm -f "$tmp"
    return 0
}

hcloud_post_json() {
    local path="$1" payload="$2"
    local tmp http
    tmp=$(mktemp)
    http=$(curl -sS -o "$tmp" -w '%{http_code}' -X POST \
            -H "Authorization: Bearer ${HCLOUD_TOKEN}" \
            -H "Content-Type: application/json" \
            -d "$payload" \
            "${API}${path}")
    if [ "$http" -ge 400 ]; then
        log_err "POST ${path} failed (HTTP ${http}):"
        cat "$tmp" >&2
        rm -f "$tmp"
        return 2
    fi
    cat "$tmp"
    rm -f "$tmp"
}

# ---------------------------------------------------------------------------
# URL-encoding helper for label selectors (= → %3D, same as cloud-reaper.sh)
# ---------------------------------------------------------------------------
encode_selector() {
    printf '%s' "$1" | sed 's/=/%3D/g'
}

# ---------------------------------------------------------------------------
# Find existing PG VM (echoes "id<TAB>name<TAB>public_ip" or empty)
# ---------------------------------------------------------------------------
find_pg_vm() {
    local enc body
    enc=$(encode_selector "$SELECTOR")
    if ! body=$(hcloud_get "/servers?label_selector=${enc}&per_page=50"); then
        return 2
    fi
    printf '%s\n' "$body" | jq -r '
        .servers[]?
        | [ (.id | tostring),
            (.name // ""),
            (.public_net.ipv4.ip // "") ]
        | @tsv'
}

# ---------------------------------------------------------------------------
# SSH key handling
# ---------------------------------------------------------------------------
# Resolve public key, deriving from private if .pub doesn't exist.
resolve_ssh_pubkey() {
    local priv="$1" pub="${1}.pub"
    if [ -f "$pub" ]; then
        cat "$pub"
        return 0
    fi
    if [ ! -f "$priv" ]; then
        log_err "AETHER_SSH_KEY does not exist: $priv"
        return 1
    fi
    log_warn "no ${pub}; deriving from private key via ssh-keygen -y -f"
    if ! ssh-keygen -y -f "$priv" > "$pub" 2>/dev/null; then
        log_err "ssh-keygen -y -f $priv failed (encrypted key without agent?)"
        rm -f "$pub"
        return 1
    fi
    chmod 0644 "$pub"
    log_info "wrote derived public key to ${pub}"
    cat "$pub"
}

# Compute the MD5 fingerprint Hetzner uses for ssh_keys.
ssh_key_fingerprint() {
    local pub_file="$1"
    # ssh-keygen -lf prints "<bits> <hash> <comment> (<type>)". Hetzner expects the
    # MD5 colon form. Force MD5 with -E md5.
    ssh-keygen -lE md5 -f "$pub_file" 2>/dev/null \
        | awk '{print $2}' \
        | sed 's|^MD5:||'
}

# Upload SSH key to Hetzner if not already present.
# Echoes the Hetzner SSH-key id on stdout.
ensure_ssh_key_uploaded() {
    local pub_content="$1" pub_file="$2"
    local fp body existing_id

    fp=$(ssh_key_fingerprint "$pub_file" || true)
    if [ -z "$fp" ]; then
        log_err "could not compute SSH key fingerprint"
        return 2
    fi

    # See if a matching key already exists (by fingerprint).
    if ! body=$(hcloud_get "/ssh_keys?fingerprint=${fp}"); then
        return 2
    fi
    existing_id=$(printf '%s\n' "$body" | jq -r '.ssh_keys[0].id // empty')
    if [ -n "$existing_id" ]; then
        log_info "reusing existing Hetzner SSH key id=${existing_id} fp=${fp}"
        printf '%s\n' "$existing_id"
        return 0
    fi

    # Otherwise upload a new one.
    local payload
    payload=$(jq -n \
        --arg name "${SSH_KEY_NAME}-$(openssl rand -hex 3)" \
        --arg pub "$pub_content" \
        --arg cluster "$CLUSTER_LABEL" \
        '{name: $name, public_key: $pub, labels: {"aether-cluster": $cluster}}')
    log_step "uploading SSH key to Hetzner"
    if ! body=$(hcloud_post_json "/ssh_keys" "$payload"); then
        return 2
    fi
    local new_id
    new_id=$(printf '%s\n' "$body" | jq -r '.ssh_key.id')
    log_ok "uploaded SSH key id=${new_id}"
    printf '%s\n' "$new_id"
}

# ---------------------------------------------------------------------------
# Cloud-init user_data builder. Echoes raw YAML; caller base64-encodes.
# ---------------------------------------------------------------------------
build_cloud_init() {
    local pwd="$1"
    cat <<EOF
#cloud-config
package_update: true
package_upgrade: false
packages:
  - docker.io
runcmd:
  - systemctl enable docker --now
  - docker pull postgres:16
  - docker run -d --name aether-pg --restart unless-stopped \\
      -p 5432:5432 \\
      -e POSTGRES_USER=aether \\
      -e POSTGRES_PASSWORD='${pwd}' \\
      -e POSTGRES_DB=aether_forge \\
      postgres:16
EOF
}

# ---------------------------------------------------------------------------
# Wait for VM running status (Hetzner side).
# ---------------------------------------------------------------------------
wait_server_running() {
    local server_id="$1" deadline elapsed status body
    deadline=$(( $(date +%s) + 120 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if body=$(hcloud_get "/servers/${server_id}"); then
            status=$(printf '%s\n' "$body" | jq -r '.server.status')
            if [ "$status" = "running" ]; then
                log_ok "server ${server_id} is running"
                return 0
            fi
        fi
        sleep 3
    done
    elapsed=$(( $(date +%s) - deadline + 120 ))
    log_err "server ${server_id} did not reach 'running' within ${elapsed}s"
    return 2
}

# ---------------------------------------------------------------------------
# Wait for TCP port 5432 reachable.
# ---------------------------------------------------------------------------
wait_pg_port() {
    local ip="$1" deadline
    deadline=$(( $(date +%s) + 240 ))  # 4 min: cloud-init + apt + docker pull + pg start
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if (exec 3<>/dev/tcp/"$ip"/5432) 2>/dev/null; then
            exec 3<&- 3>&-
            log_ok "PostgreSQL port 5432 is reachable on ${ip}"
            return 0
        fi
        sleep 5
    done
    log_err "PostgreSQL port 5432 did not become reachable on ${ip} within 240s"
    return 2
}

# ---------------------------------------------------------------------------
# Smoke test the PG instance via SSH `docker exec` (no local psql required).
# ---------------------------------------------------------------------------
smoke_test_pg() {
    local ip="$1" pwd="$2"
    log_step "smoke test: SELECT 1 via SSH docker exec"
    local out
    if ! out=$(ssh -o StrictHostKeyChecking=no \
                   -o UserKnownHostsFile=/dev/null \
                   -o ConnectTimeout=10 \
                   -o LogLevel=ERROR \
                   -i "$AETHER_SSH_KEY" \
                   "${AETHER_SSH_USER}@${ip}" \
                   "docker exec -e PGPASSWORD='${pwd}' aether-pg psql -U aether -d aether_forge -tAc 'SELECT 1'" 2>&1); then
        log_err "smoke test failed:"
        printf '%s\n' "$out" >&2
        return 2
    fi
    if [ "$(printf '%s' "$out" | tr -d '[:space:]')" != "1" ]; then
        log_err "smoke test returned unexpected output: '$out'"
        return 2
    fi
    log_ok "smoke test passed: PostgreSQL accepts queries"
}

# ---------------------------------------------------------------------------
# Persist env file (mode 0600).
# ---------------------------------------------------------------------------
write_env_file() {
    local server_id="$1" ip="$2" pwd="$3"
    local pg_url ts
    pg_url="postgres://aether:${pwd}@${ip}:5432/aether_forge"
    ts=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    umask 077
    cat > "$ENV_FILE" <<EOF
# Aether test-pg VM — auto-generated by tools/provision-test-pg.sh
# PG VM ${server_id}  IP=${ip}  provisioned=${ts}
export PG_URL=${pg_url}
EOF
    chmod 0600 "$ENV_FILE"
    log_info "wrote ${ENV_FILE} (mode 0600)"
    # Echo the same block to stdout so callers can capture/source as needed.
    cat "$ENV_FILE"
}

# ---------------------------------------------------------------------------
# Destroy path: remove server + SSH key + env file.
# ---------------------------------------------------------------------------
destroy_pg_vm() {
    local lines failures=0
    log_info "looking up PG VM (selector: ${SELECTOR})"
    if ! lines=$(find_pg_vm); then
        log_err "Hetzner API listing failed"
        return 2
    fi

    if [ -z "$lines" ]; then
        log_info "no PG VM found — nothing to destroy"
    else
        while IFS=$'\t' read -r id name _ip; do
            [ -z "$id" ] && continue
            log_step "delete server ${id} (${name})"
            if hcloud_delete "/servers/${id}"; then
                log_ok "server ${id} deleted"
            else
                failures=$((failures + 1))
            fi
        done <<< "$lines"
    fi

    # Clean up SSH keys with our cluster label.
    local enc body
    enc=$(encode_selector "$SELECTOR")
    if body=$(hcloud_get "/ssh_keys?label_selector=${enc}&per_page=50"); then
        local keys
        keys=$(printf '%s\n' "$body" | jq -r '.ssh_keys[]? | [(.id|tostring),(.name//"")] | @tsv')
        if [ -n "$keys" ]; then
            while IFS=$'\t' read -r kid kname; do
                [ -z "$kid" ] && continue
                log_step "delete ssh_key ${kid} (${kname})"
                if hcloud_delete "/ssh_keys/${kid}"; then
                    log_ok "ssh_key ${kid} deleted"
                else
                    failures=$((failures + 1))
                fi
            done <<< "$keys"
        fi
    fi

    if [ -f "$ENV_FILE" ]; then
        rm -f "$ENV_FILE"
        log_info "removed ${ENV_FILE}"
    fi

    if [ "$failures" -gt 0 ]; then
        log_err "destroy completed with ${failures} failure(s)"
        return 2
    fi
    log_ok "destroy complete"
    return 0
}

# ---------------------------------------------------------------------------
# Print-only path: emit env file if VM exists, else exit 1.
# ---------------------------------------------------------------------------
print_only() {
    local lines
    if ! lines=$(find_pg_vm); then
        log_err "Hetzner API listing failed"
        exit 2
    fi
    if [ -z "$lines" ]; then
        log_err "no PG VM provisioned (selector: ${SELECTOR})"
        log_err "run 'provision-test-pg.sh' (no flags) to provision one"
        exit 1
    fi
    if [ ! -f "$ENV_FILE" ]; then
        log_err "PG VM exists but ${ENV_FILE} is missing"
        log_err "password is unrecoverable from this side; redeploy with --fresh"
        exit 1
    fi
    cat "$ENV_FILE"
    exit 0
}

# ---------------------------------------------------------------------------
# Reuse path: VM exists → reprint env file.
# ---------------------------------------------------------------------------
reuse_existing() {
    local server_id="$1" name="$2" ip="$3"
    log_info "found existing PG VM id=${server_id} name=${name} ip=${ip}"
    if [ ! -f "$ENV_FILE" ]; then
        log_err "PG VM exists but ${ENV_FILE} is missing"
        log_err "the password is not recoverable from outside the VM"
        log_err "run --fresh to redeploy with a new password, or recover the password manually"
        exit 1
    fi
    log_ok "reusing existing VM (env file present)"
    cat "$ENV_FILE"
    exit 0
}

# ---------------------------------------------------------------------------
# Fresh provision path.
# ---------------------------------------------------------------------------
provision_fresh() {
    local pubkey pubfile pwd ssh_key_id user_data b64 payload body server_id ip vm_name suffix

    pubfile="${AETHER_SSH_KEY}.pub"
    if ! pubkey=$(resolve_ssh_pubkey "$AETHER_SSH_KEY"); then
        exit 1
    fi

    if ! ssh_key_id=$(ensure_ssh_key_uploaded "$pubkey" "$pubfile"); then
        exit 2
    fi

    pwd=$(openssl rand -hex 16)
    user_data=$(build_cloud_init "$pwd")
    b64=$(printf '%s' "$user_data" | base64 | tr -d '\n')

    suffix=$(openssl rand -hex 3)
    vm_name="${VM_NAME_PREFIX}-${suffix}"

    log_step "creating server ${vm_name} (${PG_VM_TYPE} @ ${PG_VM_LOCATION}, image ${PG_VM_IMAGE})"
    payload=$(jq -n \
        --arg name "$vm_name" \
        --arg image "$PG_VM_IMAGE" \
        --arg type "$PG_VM_TYPE" \
        --arg location "$PG_VM_LOCATION" \
        --arg user_data "$user_data" \
        --arg cluster "$CLUSTER_LABEL" \
        --arg role "$ROLE_LABEL" \
        --argjson ssh_key_id "$ssh_key_id" \
        '{
            name: $name,
            image: $image,
            server_type: $type,
            location: $location,
            ssh_keys: [$ssh_key_id],
            user_data: $user_data,
            labels: {"aether-cluster": $cluster, "aether-role": $role},
            start_after_create: true
        }')

    if ! body=$(hcloud_post_json "/servers" "$payload"); then
        exit 2
    fi

    server_id=$(printf '%s\n' "$body" | jq -r '.server.id')
    ip=$(printf '%s\n' "$body" | jq -r '.server.public_net.ipv4.ip')
    log_info "created server id=${server_id} ip=${ip}"

    if ! wait_server_running "$server_id"; then
        exit 2
    fi

    log_step "waiting for cloud-init + Docker + PostgreSQL to come up (~60-180s)"
    if ! wait_pg_port "$ip"; then
        log_warn "port reachable check timed out — VM may still be initializing; check via SSH"
        exit 2
    fi

    if ! smoke_test_pg "$ip" "$pwd"; then
        exit 2
    fi

    write_env_file "$server_id" "$ip" "$pwd"
    log_ok "PG VM ready: id=${server_id} ip=${ip}"
}

# ---------------------------------------------------------------------------
# Main flow
# ---------------------------------------------------------------------------
log_info "mode: ${MODE}"
log_info "selector: ${SELECTOR}"
log_info "endpoint: ${API}"

case "$MODE" in
    print-only)
        print_only
        ;;
    destroy)
        destroy_pg_vm
        exit $?
        ;;
    fresh)
        log_warn "--fresh: destroying any existing PG VM first"
        destroy_pg_vm || {
            log_err "pre-destroy failed; aborting before fresh provision"
            exit 2
        }
        provision_fresh
        ;;
    reuse)
        # Look up first.
        if ! lines=$(find_pg_vm); then
            exit 2
        fi
        if [ -z "$lines" ]; then
            log_info "no existing PG VM — provisioning fresh"
            provision_fresh
        else
            # Take the first row.
            first=$(printf '%s\n' "$lines" | head -n1)
            IFS=$'\t' read -r srv_id srv_name srv_ip <<< "$first"
            reuse_existing "$srv_id" "$srv_name" "$srv_ip"
        fi
        ;;
esac

exit 0
