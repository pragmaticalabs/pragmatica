#!/usr/bin/env bash
# build-aether-vm-snapshot.sh — Build a Hetzner VM snapshot pre-loaded with Aether
# runtime artifacts (Docker + aether-node image, or JDK + JAR), so subsequent VM
# provisioning skips apt-update + image pull / JAR download. See
# aether/docs/operator/vm-snapshot.md for the operator workflow.
#
# Same design philosophy as tools/provision-test-pg.sh and tools/cloud-reaper.sh:
# pure Hetzner-API + ssh, no dependency on the `aether` CLI or local bootstrap state.
#
# Snapshots are tagged with labels:
#   aether-snapshot=true          (filterable selector for cleanup tooling)
#   aether-version=<version>      (the Aether release this snapshot was built for)
#   aether-runtime=container|jvm  (which runtime payload it carries)

set -euo pipefail

# ---------------------------------------------------------------------------
# Colors / logging (mirrors tools/provision-test-pg.sh)
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

log_info() { printf '%b[INFO]%b  %s\n'  "$GREEN"  "$NC" "$1" >&2; }
log_warn() { printf '%b[WARN]%b  %s\n'  "$YELLOW" "$NC" "$1" >&2; }
log_err()  { printf '%b[ERROR]%b %s\n'  "$RED"    "$NC" "$1" >&2; }
log_step() { printf '%b[STEP]%b  %s\n'  "$BLUE"   "$NC" "$1" >&2; }
log_ok()   { printf '%b[ OK ]%b  %s\n'  "$GREEN"  "$NC" "$1" >&2; }

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
usage() {
    cat <<'EOF'
build-aether-vm-snapshot.sh — Build a Hetzner snapshot pre-loaded with Aether runtime.

USAGE
    build-aether-vm-snapshot.sh [build]    [--runtime container|jvm] [--version V]
    build-aether-vm-snapshot.sh list       [--runtime container|jvm] [--version V]
    build-aether-vm-snapshot.sh latest     [--runtime container|jvm] [--version V]
    build-aether-vm-snapshot.sh destroy    --id <snapshot-id>
    build-aether-vm-snapshot.sh prune-old  [--runtime ...] [--version V] [--keep N]

COMMANDS
    build         Provision a temporary VM, install runtime payload, snapshot the VM,
                  delete the VM, print the new snapshot id on stdout.
    list          List existing aether snapshots matching the filters.
    latest        Print the most recent snapshot id matching the filters.
    destroy       Delete a snapshot by id.
    prune-old     Keep the N most recent snapshots matching filters; delete older.

FLAGS
    --runtime container | jvm   Payload to bake into the snapshot (default: container).
    --version V                 Aether version label (default: derived from pom.xml).
    --image NAME                Override base OS image (default: ubuntu-22.04).
    --vm-type TYPE              Override Hetzner server type (default: cx23).
    --location LOC              Override Hetzner location (default: fsn1).
    --jar-url URL               Override JAR download URL (jvm runtime only).
                                Default: GitHub release for the resolved version.
    --aether-image IMG          Override container image (container runtime only).
                                Default: ghcr.io/pragmaticalabs/aether-node:<version>.
    --keep N                    For prune-old: how many recent snapshots to keep.
    --id ID                     For destroy: the snapshot id to delete.
    --help, -h                  Print this help and exit.

ENVIRONMENT
    HCLOUD_TOKEN     Required. Hetzner Cloud API token.
    AETHER_SSH_KEY   Required for `build`. Path to SSH private key (matching .pub
                     used to authorize SSH onto the build VM).
    AETHER_VERSION   Optional. Overrides --version detection.

OUTPUT (build)
    On success, prints the snapshot id (Hetzner image id, integer) on stdout.
    Operators can plumb this into source TOMLs:
        [source.<provider>.<role>]
        image = "<snapshot-id>"

EXAMPLES
    # Build a container-runtime snapshot for the version in pom.xml
    build-aether-vm-snapshot.sh build

    # Build a JVM-runtime snapshot
    build-aether-vm-snapshot.sh build --runtime jvm

    # See existing snapshots for this version
    build-aether-vm-snapshot.sh list

    # Get the most recent snapshot id (for scripts/CI)
    SNAP=$(build-aether-vm-snapshot.sh latest --runtime container)

    # Garbage-collect old snapshots, keep the 3 newest per (version, runtime)
    build-aether-vm-snapshot.sh prune-old --keep 3

EXIT CODES
    0  — success
    1  — usage / setup error
    2  — Hetzner API failure / build failure
EOF
}

# ---------------------------------------------------------------------------
# Constants / defaults
# ---------------------------------------------------------------------------
readonly API="https://api.hetzner.cloud/v1"
readonly SSH_KEY_NAME_PREFIX="aether-snapshot-build"
readonly VM_NAME_PREFIX="aether-snapshot-build"
readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
readonly REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

VM_TYPE="cx23"
VM_LOCATION="fsn1"
VM_IMAGE="ubuntu-22.04"
RUNTIME="container"
KEEP_N=3
SNAPSHOT_ID=""
JAR_URL_OVERRIDE=""
AETHER_IMAGE_OVERRIDE=""
VERSION_OVERRIDE=""

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
COMMAND="${1:-build}"
case "$COMMAND" in
    build|list|latest|destroy|prune-old)
        shift
        ;;
    -h|--help)
        usage; exit 0
        ;;
    *)
        # Treat as default `build` when the first arg is a flag.
        if [[ "$COMMAND" == --* ]]; then
            COMMAND="build"
        else
            log_err "unknown command: $COMMAND"
            usage >&2
            exit 1
        fi
        ;;
esac

while [ $# -gt 0 ]; do
    case "$1" in
        --runtime)       RUNTIME="$2"; shift 2 ;;
        --runtime=*)     RUNTIME="${1#*=}"; shift ;;
        --version)       VERSION_OVERRIDE="$2"; shift 2 ;;
        --version=*)     VERSION_OVERRIDE="${1#*=}"; shift ;;
        --image)         VM_IMAGE="$2"; shift 2 ;;
        --image=*)       VM_IMAGE="${1#*=}"; shift ;;
        --vm-type)       VM_TYPE="$2"; shift 2 ;;
        --vm-type=*)     VM_TYPE="${1#*=}"; shift ;;
        --location)      VM_LOCATION="$2"; shift 2 ;;
        --location=*)    VM_LOCATION="${1#*=}"; shift ;;
        --jar-url)       JAR_URL_OVERRIDE="$2"; shift 2 ;;
        --jar-url=*)     JAR_URL_OVERRIDE="${1#*=}"; shift ;;
        --aether-image)  AETHER_IMAGE_OVERRIDE="$2"; shift 2 ;;
        --aether-image=*) AETHER_IMAGE_OVERRIDE="${1#*=}"; shift ;;
        --keep)          KEEP_N="$2"; shift 2 ;;
        --keep=*)        KEEP_N="${1#*=}"; shift ;;
        --id)            SNAPSHOT_ID="$2"; shift 2 ;;
        --id=*)          SNAPSHOT_ID="${1#*=}"; shift ;;
        --help|-h)       usage; exit 0 ;;
        *)
            log_err "unknown argument: $1"
            usage >&2
            exit 1
            ;;
    esac
done

case "$RUNTIME" in
    container|jvm) ;;
    *) log_err "--runtime must be 'container' or 'jvm', got: ${RUNTIME}"; exit 1 ;;
esac

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
for dep in curl jq openssl; do
    if ! command -v "$dep" >/dev/null 2>&1; then
        log_err "missing dependency: $dep"
        exit 1
    fi
done

if [ -z "${HCLOUD_TOKEN:-}" ]; then
    log_err "HCLOUD_TOKEN is not set."
    exit 1
fi

# ---------------------------------------------------------------------------
# Resolve Aether version
# ---------------------------------------------------------------------------
resolve_version() {
    if [ -n "$VERSION_OVERRIDE" ]; then
        printf '%s\n' "$VERSION_OVERRIDE"
        return 0
    fi
    if [ -n "${AETHER_VERSION:-}" ]; then
        printf '%s\n' "$AETHER_VERSION"
        return 0
    fi
    # Fall back to root pom.xml. Same parser the rest of the repo uses (mvn -q
    # exec works too but adds a Java dep; this is a 1-line grep).
    local pom="${REPO_ROOT}/pom.xml"
    if [ ! -f "$pom" ]; then
        log_err "cannot find $pom; pass --version explicitly"
        exit 1
    fi
    # First <version> element under <project> (skips <parent>/<version>).
    awk '
        /<project/         { in_project = 1 }
        in_project && /<parent>/   { in_parent = 1 }
        in_parent && /<\/parent>/  { in_parent = 0; next }
        in_project && !in_parent && /<version>/ {
            match($0, /<version>([^<]+)<\/version>/, m)
            if (m[1] != "") { print m[1]; exit }
        }
    ' "$pom"
}

AETHER_VERSION_RESOLVED=$(resolve_version)
if [ -z "$AETHER_VERSION_RESOLVED" ]; then
    log_err "could not resolve Aether version; pass --version"
    exit 1
fi

# ---------------------------------------------------------------------------
# Resolve container image / jar URL defaults
# Mirrors UserDataTemplate.deriveJarTag and the default container image in
# UserDataTemplate.render — keep these consistent.
# ---------------------------------------------------------------------------
derive_jar_tag() {
    local v="$1"
    if [[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        printf 'v%s\n' "$v"
    else
        printf 'v%s-candidate\n' "$v"
    fi
}

resolve_jar_url() {
    if [ -n "$JAR_URL_OVERRIDE" ]; then
        printf '%s\n' "$JAR_URL_OVERRIDE"
        return 0
    fi
    local tag
    tag=$(derive_jar_tag "$AETHER_VERSION_RESOLVED")
    printf 'https://github.com/pragmaticalabs/pragmatica/releases/download/%s/aether-node.jar\n' "$tag"
}

resolve_aether_image() {
    if [ -n "$AETHER_IMAGE_OVERRIDE" ]; then
        printf '%s\n' "$AETHER_IMAGE_OVERRIDE"
        return 0
    fi
    printf 'ghcr.io/pragmaticalabs/aether-node:%s\n' "$AETHER_VERSION_RESOLVED"
}

# ---------------------------------------------------------------------------
# HTTP wrappers (same shape as provision-test-pg.sh)
# ---------------------------------------------------------------------------
hcloud_get() {
    local path="$1" tmp http
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
    local path="$1" tmp http
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
    local path="$1" payload="$2" tmp http
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

encode_selector() {
    printf '%s' "$1" | sed 's/=/%3D/g'
}

# ---------------------------------------------------------------------------
# Snapshot listing / pruning / destruction
# ---------------------------------------------------------------------------
list_snapshots() {
    local enc body
    # Hetzner /images supports type=snapshot + label_selector.
    enc=$(encode_selector "aether-snapshot=true")
    if ! body=$(hcloud_get "/images?type=snapshot&label_selector=${enc}&per_page=50"); then
        return 2
    fi
    # Filter by --runtime / --version on the client side.
    printf '%s\n' "$body" | jq -r --arg ver "$AETHER_VERSION_RESOLVED" --arg rt "$RUNTIME" '
        .images[]?
        | select(.labels["aether-version"] == $ver)
        | select(.labels["aether-runtime"] == $rt)
        | [(.id|tostring), .created, (.description // ""), (.labels["aether-version"] // ""), (.labels["aether-runtime"] // "")]
        | @tsv'
}

cmd_list() {
    local rows
    if ! rows=$(list_snapshots); then
        exit 2
    fi
    if [ -z "$rows" ]; then
        log_info "no aether snapshots match version=${AETHER_VERSION_RESOLVED} runtime=${RUNTIME}"
        return 0
    fi
    printf '%-12s  %-25s  %-10s  %s\n' "ID" "CREATED" "RUNTIME" "DESCRIPTION"
    printf '%s\n' "$rows" | sort -k2 -r | while IFS=$'\t' read -r id created desc ver rt; do
        printf '%-12s  %-25s  %-10s  %s\n' "$id" "$created" "$rt" "$desc"
    done
}

cmd_latest() {
    local rows
    if ! rows=$(list_snapshots); then
        exit 2
    fi
    if [ -z "$rows" ]; then
        log_err "no aether snapshots match version=${AETHER_VERSION_RESOLVED} runtime=${RUNTIME}"
        exit 1
    fi
    # Sort by created desc, take first id.
    printf '%s\n' "$rows" | sort -k2 -r | head -n1 | cut -f1
}

cmd_destroy() {
    if [ -z "$SNAPSHOT_ID" ]; then
        log_err "destroy: --id <snapshot-id> required"
        exit 1
    fi
    log_step "deleting snapshot ${SNAPSHOT_ID}"
    if hcloud_delete "/images/${SNAPSHOT_ID}"; then
        log_ok "snapshot ${SNAPSHOT_ID} deleted"
    else
        exit 2
    fi
}

cmd_prune_old() {
    local rows
    if ! rows=$(list_snapshots); then
        exit 2
    fi
    if [ -z "$rows" ]; then
        log_info "no aether snapshots to prune"
        return 0
    fi
    local sorted
    sorted=$(printf '%s\n' "$rows" | sort -k2 -r)
    local total
    total=$(printf '%s\n' "$sorted" | wc -l | tr -d ' ')
    log_info "found ${total} snapshots; keeping ${KEEP_N} newest"
    if [ "$total" -le "$KEEP_N" ]; then
        log_info "nothing to prune"
        return 0
    fi
    printf '%s\n' "$sorted" | tail -n "+$((KEEP_N + 1))" | while IFS=$'\t' read -r id created desc ver rt; do
        log_step "deleting snapshot ${id} (created ${created})"
        if ! hcloud_delete "/images/${id}"; then
            log_warn "failed to delete ${id}; continuing"
        fi
    done
}

# ---------------------------------------------------------------------------
# SSH key handling — derive from AETHER_SSH_KEY (same as provision-test-pg.sh)
# ---------------------------------------------------------------------------
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
    cat "$pub"
}

ssh_key_fingerprint() {
    local pub_file="$1"
    ssh-keygen -lE md5 -f "$pub_file" 2>/dev/null \
        | awk '{print $2}' \
        | sed 's|^MD5:||'
}

ensure_ssh_key_uploaded() {
    local pub_content="$1" pub_file="$2"
    local fp body existing_id

    fp=$(ssh_key_fingerprint "$pub_file" || true)
    if [ -z "$fp" ]; then
        log_err "could not compute SSH key fingerprint"
        return 2
    fi

    if ! body=$(hcloud_get "/ssh_keys?fingerprint=${fp}"); then
        return 2
    fi
    existing_id=$(printf '%s\n' "$body" | jq -r '.ssh_keys[0].id // empty')
    if [ -n "$existing_id" ]; then
        printf '%s\n' "$existing_id"
        return 0
    fi

    local payload
    payload=$(jq -n \
        --arg name "${SSH_KEY_NAME_PREFIX}-$(openssl rand -hex 3)" \
        --arg pub "$pub_content" \
        '{name: $name, public_key: $pub, labels: {"aether-snapshot-build": "true"}}')
    log_step "uploading SSH key to Hetzner"
    if ! body=$(hcloud_post_json "/ssh_keys" "$payload"); then
        return 2
    fi
    printf '%s\n' "$body" | jq -r '.ssh_key.id'
}

# ---------------------------------------------------------------------------
# Cloud-init payload — preinstall the runtime artifact, signal completion
# via a marker file we can SSH-poll.
# ---------------------------------------------------------------------------
build_cloud_init_container() {
    local image="$1"
    cat <<EOF
#cloud-config
package_update: true
package_upgrade: false
runcmd:
  - if ! command -v docker >/dev/null 2>&1; then curl -fsSL https://get.docker.com | sh; fi
  - docker pull "${image}"
  - mkdir -p /opt/aether
  - 'echo "image=${image}" > /opt/aether/.snapshot-prepared'
  - 'echo "version=${AETHER_VERSION_RESOLVED}" >> /opt/aether/.snapshot-prepared'
  - 'echo "runtime=container" >> /opt/aether/.snapshot-prepared'
  - 'echo "built_at=\$(date -u +%FT%TZ)" >> /opt/aether/.snapshot-prepared'
EOF
}

build_cloud_init_jvm() {
    local jar_url="$1"
    cat <<EOF
#cloud-config
package_update: true
package_upgrade: false
runcmd:
  - apt-get update -qq
  - apt-get install -y -qq wget gnupg ca-certificates apt-transport-https curl
  - mkdir -p /etc/apt/keyrings
  - wget -qO /etc/apt/keyrings/adoptium.asc https://packages.adoptium.net/artifactory/api/gpg/key/public
  - bash -c 'CODENAME=\$(. /etc/os-release && echo "\${VERSION_CODENAME}"); echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb \${CODENAME} main" > /etc/apt/sources.list.d/adoptium.list'
  - apt-get update -qq
  - apt-get install -y -qq temurin-25-jre
  - mkdir -p /opt/aether
  - curl -fsSL -o /opt/aether/aether-node.jar "${jar_url}"
  - 'echo "jar_url=${jar_url}" > /opt/aether/.snapshot-prepared'
  - 'echo "version=${AETHER_VERSION_RESOLVED}" >> /opt/aether/.snapshot-prepared'
  - 'echo "runtime=jvm" >> /opt/aether/.snapshot-prepared'
  - 'echo "built_at=\$(date -u +%FT%TZ)" >> /opt/aether/.snapshot-prepared'
EOF
}

# ---------------------------------------------------------------------------
# Server lifecycle helpers
# ---------------------------------------------------------------------------
wait_server_running() {
    local server_id="$1" deadline status body
    deadline=$(( $(date +%s) + 180 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if body=$(hcloud_get "/servers/${server_id}"); then
            status=$(printf '%s\n' "$body" | jq -r '.server.status')
            if [ "$status" = "running" ]; then
                return 0
            fi
        fi
        sleep 3
    done
    log_err "server ${server_id} did not reach 'running' within 180s"
    return 2
}

wait_server_status() {
    local server_id="$1" want="$2" deadline status body
    deadline=$(( $(date +%s) + 180 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if body=$(hcloud_get "/servers/${server_id}"); then
            status=$(printf '%s\n' "$body" | jq -r '.server.status')
            if [ "$status" = "$want" ]; then
                return 0
            fi
        fi
        sleep 3
    done
    log_err "server ${server_id} did not reach '${want}' within 180s"
    return 2
}

wait_action_finished() {
    local action_id="$1" deadline status body
    deadline=$(( $(date +%s) + 600 ))  # snapshot creation can be slow
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if body=$(hcloud_get "/actions/${action_id}"); then
            status=$(printf '%s\n' "$body" | jq -r '.action.status')
            case "$status" in
                success) return 0 ;;
                error) log_err "action ${action_id} failed:"; printf '%s\n' "$body" | jq '.action' >&2; return 2 ;;
            esac
        fi
        sleep 5
    done
    log_err "action ${action_id} did not finish within 600s"
    return 2
}

wait_cloud_init_done() {
    local ip="$1" deadline
    deadline=$(( $(date +%s) + 600 ))  # cloud-init + apt + Docker install + image pull
    log_step "waiting for cloud-init to mark snapshot prepared (~3-8 min)"
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if ssh -o StrictHostKeyChecking=no \
               -o UserKnownHostsFile=/dev/null \
               -o ConnectTimeout=5 \
               -o LogLevel=ERROR \
               -i "$AETHER_SSH_KEY" \
               "root@${ip}" \
               "test -f /opt/aether/.snapshot-prepared && cat /opt/aether/.snapshot-prepared" 2>/dev/null; then
            return 0
        fi
        sleep 10
    done
    log_err "cloud-init did not produce /opt/aether/.snapshot-prepared within 600s"
    return 2
}

# ---------------------------------------------------------------------------
# Build path
# ---------------------------------------------------------------------------
cmd_build() {
    if [ -z "${AETHER_SSH_KEY:-}" ]; then
        log_err "AETHER_SSH_KEY is not set."
        exit 1
    fi

    local pubfile pubkey ssh_key_id user_data b64 payload body server_id ip vm_name suffix
    local image_label jar_url
    pubfile="${AETHER_SSH_KEY}.pub"
    if ! pubkey=$(resolve_ssh_pubkey "$AETHER_SSH_KEY"); then
        exit 1
    fi
    if ! ssh_key_id=$(ensure_ssh_key_uploaded "$pubkey" "$pubfile"); then
        exit 2
    fi

    case "$RUNTIME" in
        container)
            image_label=$(resolve_aether_image)
            user_data=$(build_cloud_init_container "$image_label")
            ;;
        jvm)
            jar_url=$(resolve_jar_url)
            user_data=$(build_cloud_init_jvm "$jar_url")
            ;;
    esac

    suffix=$(openssl rand -hex 3)
    vm_name="${VM_NAME_PREFIX}-${RUNTIME}-${suffix}"

    log_step "creating build VM ${vm_name} (${VM_TYPE} @ ${VM_LOCATION}, image ${VM_IMAGE})"
    log_info "runtime=${RUNTIME}  aether-version=${AETHER_VERSION_RESOLVED}"
    if [ "$RUNTIME" = "container" ]; then
        log_info "container image: ${image_label}"
    else
        log_info "jar url: ${jar_url}"
    fi

    payload=$(jq -n \
        --arg name "$vm_name" \
        --arg image "$VM_IMAGE" \
        --arg type "$VM_TYPE" \
        --arg location "$VM_LOCATION" \
        --arg user_data "$user_data" \
        --argjson ssh_key_id "$ssh_key_id" \
        '{
            name: $name,
            image: $image,
            server_type: $type,
            location: $location,
            ssh_keys: [$ssh_key_id],
            user_data: $user_data,
            labels: {"aether-snapshot-build": "true"},
            start_after_create: true
        }')

    if ! body=$(hcloud_post_json "/servers" "$payload"); then
        exit 2
    fi
    server_id=$(printf '%s\n' "$body" | jq -r '.server.id')
    ip=$(printf '%s\n' "$body" | jq -r '.server.public_net.ipv4.ip')
    log_info "created build VM id=${server_id} ip=${ip}"

    # Cleanup trap: best-effort delete VM on any error past this point.
    cleanup_vm() {
        if [ -n "${server_id:-}" ]; then
            log_warn "cleaning up build VM ${server_id} after error"
            hcloud_delete "/servers/${server_id}" >/dev/null 2>&1 || true
        fi
    }
    trap cleanup_vm ERR

    if ! wait_server_running "$server_id"; then
        exit 2
    fi

    if ! wait_cloud_init_done "$ip"; then
        exit 2
    fi
    log_ok "snapshot payload prepared on build VM"

    log_step "powering off build VM ${server_id}"
    local poweroff_body action_id
    if ! poweroff_body=$(hcloud_post_json "/servers/${server_id}/actions/poweroff" '{}'); then
        exit 2
    fi
    action_id=$(printf '%s\n' "$poweroff_body" | jq -r '.action.id')
    if ! wait_action_finished "$action_id"; then
        exit 2
    fi
    if ! wait_server_status "$server_id" "off"; then
        exit 2
    fi
    log_ok "build VM stopped"

    log_step "creating snapshot from build VM"
    local description
    description="aether-${AETHER_VERSION_RESOLVED}-${RUNTIME} (built $(date -u +%FT%TZ))"
    local snap_payload snap_body
    snap_payload=$(jq -n \
        --arg desc "$description" \
        --arg ver "$AETHER_VERSION_RESOLVED" \
        --arg rt "$RUNTIME" \
        '{
            type: "snapshot",
            description: $desc,
            labels: {
                "aether-snapshot": "true",
                "aether-version": $ver,
                "aether-runtime": $rt
            }
        }')
    if ! snap_body=$(hcloud_post_json "/servers/${server_id}/actions/create_image" "$snap_payload"); then
        exit 2
    fi
    local snap_action_id snapshot_id
    snap_action_id=$(printf '%s\n' "$snap_body" | jq -r '.action.id')
    snapshot_id=$(printf '%s\n' "$snap_body" | jq -r '.image.id')
    log_info "snapshot creation queued: id=${snapshot_id} action=${snap_action_id}"
    if ! wait_action_finished "$snap_action_id"; then
        log_err "snapshot action ${snap_action_id} did not complete cleanly"
        exit 2
    fi
    log_ok "snapshot ${snapshot_id} created (${description})"

    log_step "deleting build VM ${server_id}"
    if ! hcloud_delete "/servers/${server_id}"; then
        log_warn "failed to delete build VM ${server_id}; you may need to clean it up manually"
    else
        log_ok "build VM ${server_id} deleted"
    fi
    trap - ERR

    # Final stdout: the snapshot id, for easy capture in scripts.
    printf '%s\n' "$snapshot_id"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
log_info "command: ${COMMAND}"
log_info "runtime: ${RUNTIME}  version: ${AETHER_VERSION_RESOLVED}"

case "$COMMAND" in
    build)      cmd_build ;;
    list)       cmd_list ;;
    latest)     cmd_latest ;;
    destroy)    cmd_destroy ;;
    prune-old)  cmd_prune_old ;;
    *)
        log_err "unhandled command: ${COMMAND}"
        exit 1
        ;;
esac

exit 0
