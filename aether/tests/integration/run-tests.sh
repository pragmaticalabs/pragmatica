#!/bin/bash
# run-tests.sh -- Integration test runner with dual-cluster support
#
# Usage:
#   ./run-tests.sh --env docker                          # Run all tests locally
#   ./run-tests.sh --env remote --suites 00,02           # Run specific suites on remote host
#   ./run-tests.sh --env docker --skip-deploy            # Skip cluster provisioning
#   ./run-tests.sh --env cloud --skip-teardown           # Keep cloud clusters running
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# Set TARGET_HOST default before sourcing common.sh (which requires it)
: "${TARGET_HOST:=localhost}"
export TARGET_HOST

source "${SCRIPT_DIR}/lib/common.sh"
source "${SCRIPT_DIR}/lib/cluster.sh"
source "${SCRIPT_DIR}/lib/suite.sh"

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
ENV_TYPE=""
SELECTED_SUITES=""
SKIP_BUILD=false
SKIP_DEPLOY=false
SKIP_TEARDOWN=false
SKIP_IMAGE_PUSH=false

RESULTS_FILE="$(mktemp /tmp/aether-test-results.XXXXXX)"
RESULTS_JSON="${SCRIPT_DIR}/test-results.json"
TIMINGS_FILE="$(mktemp /tmp/aether-test-timings.XXXXXX)"
# Child processes (suites) may record per-await durations here.
export QUIESCED_TIMINGS_FILE="$TIMINGS_FILE"

# Cluster A: non-destructive (parallel)
COMPOSE_A="${SCRIPT_DIR}/docker-compose-a.yml"
CLUSTER_A_NAME="test-a"
# Direct entry point: node-1's host-mapped mgmt port. `_resolve_live_endpoint`
# (lib/common.sh) handles failover by health-probing MGMT_PORT..MGMT_PORT+N-1
# and updating the pin to the first live node — structurally equivalent to a
# round-robin LB with failover, but at the test client. No separate gateway
# container needed (the old nginx sidecar was removed: it caused 09-artifacts
# 1MB push 504s via `proxy_request_buffering on` + `proxy_next_upstream` retry
# loop re-sending the body, plus DNS-at-config-load fragility and two configs
# to maintain). aether-node's MGMT API enforces auth + leader-forwarding at the
# handler layer, so the gateway was a redundant proxy.
CLUSTER_A_MGMT="http://${TARGET_HOST:-localhost}:5151"
# Direct (LB-less) app-HTTP fallback — node-1's host-mapped app port (see docker-compose-a.yml)
CLUSTER_A_APP_DIRECT="http://${TARGET_HOST:-localhost}:8070"
CLUSTER_A_LB_APP=""
CLUSTER_A_LB_MGMT=""

# Cluster B: destructive (sequential)
COMPOSE_B="${SCRIPT_DIR}/docker-compose-b.yml"
CLUSTER_B_NAME="test-b"
# Direct entry point: node-1's host-mapped mgmt port. Same rationale as
# CLUSTER_A_MGMT above. `_resolve_live_endpoint` failover preserves destructive-
# test resilience: if node-1 (the pinned endpoint) is killed, the resolver
# rotates through 5161..5165 and updates the pin to the first live node. With
# cluster B's `restart: "no"` policy the killed container stays dead, so the
# updated pin remains stable for the remainder of the suite.
CLUSTER_B_MGMT="http://${TARGET_HOST:-localhost}:5161"
# Direct (LB-less) app-HTTP fallback — node-1's host-mapped app port (see docker-compose-b.yml)
CLUSTER_B_APP_DIRECT="http://${TARGET_HOST:-localhost}:8080"
CLUSTER_B_LB_APP=""
CLUSTER_B_LB_MGMT=""

# Suite assignments (from spec Section 5)
CLUSTER_A_SUITES=(00 04 06 07 08 09 10 11 14 15)
# Cluster B runs sequentially with a baseline-restore between suites; a suite that
# leaves cluster B unrecoverable SKIPS all suites after it. So order by ASCENDING
# wedge risk, not numerically, to validate as many suites as possible per run:
#   05 security  — no node churn (safest)
#   13 edge      — worker/deploy ops incl Gap-C artifact-isolation (observe the known gap early)
#   12 network   — partition tests, but partitions heal (reversible)
#   03 scaling   — scale-up exercises the provisionNode path that can stall under churn
#   02 chaos     — test-kill-multiple is the proven 2-node-burst wedge → runs LAST
CLUSTER_B_SUITES=(05 13 12 03 02)

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
CLOUD_RUNTIME="container"
while [ $# -gt 0 ]; do
    case "$1" in
        --env)           ENV_TYPE="$2"; shift 2 ;;
        --env=*)         ENV_TYPE="${1#*=}"; shift ;;
        --runtime)       CLOUD_RUNTIME="$2"; shift 2 ;;
        --runtime=*)     CLOUD_RUNTIME="${1#*=}"; shift ;;
        --suites)        SELECTED_SUITES="$2"; shift 2 ;;
        --suites=*)      SELECTED_SUITES="${1#*=}"; shift ;;
        --skip-build)    SKIP_BUILD=true; shift ;;
        --skip-deploy)   SKIP_DEPLOY=true; shift ;;
        --skip-teardown) SKIP_TEARDOWN=true; shift ;;
        --skip-image-push) SKIP_IMAGE_PUSH=true; shift ;;
        -h|--help)
            echo "Usage: $0 --env docker|remote|cloud [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --env TYPE         Environment: docker, remote, or cloud (required)"
            echo "  --runtime TYPE     Cloud runtime: container (default) or jvm (cloud-only)"
            echo "  --suites X,Y       Comma-separated suite prefixes (default: all)"
            echo "  --skip-build       Skip build.sh and blueprint builds"
            echo "  --skip-deploy      Skip cluster provisioning (reuse running clusters)"
            echo "  --skip-teardown    Leave clusters running after tests"
            echo "  --skip-image-push  Skip pushing aether-node.jar + rebuilding remote image (reuse what is already on remote)"
            echo ""
            echo "Environment variables:"
            echo "  TARGET_HOST        Host for docker/remote (default: localhost)"
            echo "  AETHER_SSH_KEY     SSH key for remote env"
            echo "  HCLOUD_TOKEN       Hetzner token for cloud env"
            echo "  AETHER_API_KEY     API key (default: aether-integration-test-key)"
            echo "  AETHER_VM_SNAPSHOT_ID       Hetzner snapshot id for cloud --runtime container"
            echo "  AETHER_VM_SNAPSHOT_ID_JVM   Hetzner snapshot id for cloud --runtime jvm"
            echo "                              See aether/docs/operator/vm-snapshot.md"
            exit 0
            ;;
        *) echo "Unknown argument: $1"; exit 2 ;;
    esac
done

# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------
if [ -z "$ENV_TYPE" ]; then
    echo "ERROR: --env is required. Use --help for usage."
    exit 2
fi

case "$ENV_TYPE" in
    docker)
        : "${TARGET_HOST:=localhost}"
        export TARGET_HOST
        ;;
    remote)
        : "${TARGET_HOST:?TARGET_HOST must be set for remote env}"
        : "${AETHER_SSH_KEY:?AETHER_SSH_KEY must be set for remote env}"
        # Remote runs Docker on a Hetzner-class host: inter-node latency and
        # provisioning jitter are between docker-localhost and cloud. SWIM detection
        # chain (15s suspectTimeout + 10s reconciler cooldown + probe round-robin slip)
        # sits in the 50-60s p95 band, hard-bumping the suite's 60s detection wall
        # without scaling. Override via env if needed.
        export TIMEOUT_SCALE="${TIMEOUT_SCALE:-2}"
        ;;
    cloud)
        : "${HCLOUD_TOKEN:?HCLOUD_TOKEN must be set for cloud env}"
        # Per-node management port on cloud VMs. Each node is its own VM with mgmt on
        # a fixed port (operations.ports.management in env/cloud-hetzner*.toml = 8080;
        # `aether cluster bootstrap` emits mgmt endpoints as <publicIp>:8080). The
        # harness's per-node addressing (node_base_url, _resolve_live_endpoint,
        # status/topology helpers) reads CLOUD_MGMT_PORT — it MUST be set here because
        # MGMT_PORT is always the docker host-mapped range (5151/5161) even on cloud,
        # so any `:-MGMT_PORT` fallback would dial the wrong (dead) port. Override via
        # env only if the cloud TOML's management port is ever changed from 8080.
        export CLOUD_MGMT_PORT="${CLOUD_MGMT_PORT:-8080}"
        # Cloud has higher inter-node latency than docker-localhost: 04-streaming ran 2.7×
        # slower in the run-5 baseline, 09-artifacts ~9× slower. Scale every wait_for_*
        # / await_generation_quiesced timeout proportionally. Override via env if needed.
        export TIMEOUT_SCALE="${TIMEOUT_SCALE:-3}"
        # Privileged ops (artifact publication, #282) require OPERATOR/ADMIN auth even under
        # security_mode=NONE; the only bypass is AETHER_INSECURE_DEV_MODE on the node. The
        # docker env bakes this into docker-compose-{a,b}.yml for every node; the cloud path
        # has no TOML knob, so export it here for `aether cluster bootstrap` to read —
        # NodeUserDataRenderer.emitIdentityEnv() propagates it into each provisioned node's
        # cloud-init (docker run -e / JVM export). Without it, cluster A (security_mode=NONE)
        # rejects blueprint pushes and the 00-smoke gate fails. Override to false to enforce.
        export AETHER_INSECURE_DEV_MODE="${AETHER_INSECURE_DEV_MODE:-true}"
        # Per-key RBAC keys for 05-security viewer/operator coverage. These match the
        # VIEWER/OPERATOR api-key section suffixes in env/cloud-hetzner-b.toml (and the
        # JVM-B variant). lib/common.sh resolves VIEWER_API_KEY=${AETHER_VIEWER_API_KEY:-}
        # and OPERATOR_API_KEY=${AETHER_OPERATOR_API_KEY:-${API_KEY}} from these — without
        # them the 3 viewer tests skip and the operator test runs vacuously on the admin
        # key. Cloud-only: the docker env wires distinct-role keys via docker-compose.
        export AETHER_VIEWER_API_KEY="aether-integration-viewer-key"
        export AETHER_OPERATOR_API_KEY="aether-integration-operator-key"
        case "$CLOUD_RUNTIME" in
            container) ;;
            jvm)
                CLUSTER_A_NAME="cloud-test-a-jvm"
                CLUSTER_B_NAME="cloud-test-b-jvm"
                ;;
            *)
                echo "ERROR: --runtime must be 'container' or 'jvm', got: ${CLOUD_RUNTIME}"
                exit 2
                ;;
        esac
        ;;
    *)
        echo "ERROR: Invalid --env value: ${ENV_TYPE}. Must be docker, remote, or cloud."
        exit 2
        ;;
esac

# ---------------------------------------------------------------------------
# Filter suites if --suites provided
# ---------------------------------------------------------------------------
filter_suites() {
    local cluster_suites=("$@")
    if [ -z "$SELECTED_SUITES" ]; then
        echo "${cluster_suites[@]}"
        return
    fi
    IFS=',' read -ra selected <<< "$SELECTED_SUITES"
    local filtered=()
    for s in "${cluster_suites[@]}"; do
        for sel in "${selected[@]}"; do
            if [ "$s" = "$sel" ] || [[ "$s" == "${sel}-"* ]]; then
                filtered+=("$s")
                break
            fi
        done
    done
    echo "${filtered[@]+"${filtered[@]}"}"
}

# ---------------------------------------------------------------------------
# Resolve suite directory from a numeric prefix
# ---------------------------------------------------------------------------
resolve_suite_dir() {
    local prefix="$1"
    ls -d "${SCRIPT_DIR}/suites/${prefix}-"* 2>/dev/null | head -1
}

# ---------------------------------------------------------------------------
# Deploy blueprints to a cluster
# ---------------------------------------------------------------------------
deploy_blueprints() {
    local cluster_endpoint="$1"
    shift
    local blueprints=("$@")

    # Deduplicate
    local unique_bps=()
    local seen=""
    for bp in "${blueprints[@]}"; do
        if [[ "$seen" != *"|${bp}|"* ]]; then
            unique_bps+=("$bp")
            seen="${seen}|${bp}|"
        fi
    done

    # Preceding await_generation_quiesced on the cluster endpoint ensures Rabia
    # has activated; a single-shot deploy per blueprint is deterministic.
    for bp in "${unique_bps[@]}"; do
        local coords="org.pragmatica.aether.test:${bp}:1.0.0"
        log_info "Pushing blueprint: ${coords} to ${cluster_endpoint}"
        aether -c "${cluster_endpoint#http://}" --api-key "${API_KEY}" artifacts push "$coords" 2>/dev/null || true
        aether -c "${cluster_endpoint#http://}" --api-key "${API_KEY}" blueprints deploy "$coords" 2>&1 || \
            log_warn "blueprint deploy ${coords} did not return success (continuing)"
    done
}

# ---------------------------------------------------------------------------
# Collect blueprints needed by a list of suites
# ---------------------------------------------------------------------------
collect_blueprints() {
    local suites=("$@")
    local bps=()
    for s in "${suites[@]}"; do
        local dir
        dir=$(resolve_suite_dir "$s")
        if [ -n "$dir" ] && [ -d "$dir" ]; then
            local bp
            bp=$(suite_blueprint "$dir")
            bps+=("$bp")
        fi
    done
    echo "${bps[@]}"
}

# ---------------------------------------------------------------------------
# Run a single suite against a specific cluster
# ---------------------------------------------------------------------------
run_suite() {
    local suite_prefix="$1"
    # CRITICAL: name this `target_cluster` not `cluster`. `parse_suite_conf` sources
    # suite.conf which sets `cluster=non-destructive|destructive` at function-scope
    # via Bash dynamic scoping — clobbering a local named `cluster` here. Result
    # before this fix: cluster A suites silently fell through to cluster B's
    # endpoints + container names, killing cluster B nodes from cluster A tests.
    local target_cluster="$2"  # "a" or "b"

    # Resolve suite directory
    local suite_dir
    suite_dir=$(resolve_suite_dir "$suite_prefix")
    if [ -z "$suite_dir" ] || [ ! -d "$suite_dir" ]; then
        log_error "Suite not found: ${suite_prefix}"
        return 1
    fi

    local suite_name
    suite_name=$(basename "$suite_dir")

    # Check requirements (sources suite.conf — see scoping note above).
    if ! check_requirements "$suite_dir"; then
        log_info "SKIP: ${suite_name} (missing capabilities)"
        echo "{\"suite\":\"${suite_name}\",\"status\":\"skipped\",\"pass\":0,\"fail\":0,\"duration\":0}" >> "$RESULTS_FILE"
        return 0
    fi

    # Set cluster-specific endpoints. App-HTTP fallback points to the node-1
    # direct app port (8070/8080 per compose), not the mgmt port — slice routes
    # are only served on the app HTTP listener.
    local cluster_endpoint lb_app lb_mgmt cluster_id node_base
    if [ "$target_cluster" = "a" ]; then
        cluster_endpoint="$CLUSTER_A_MGMT"
        lb_app="${CLUSTER_A_LB_APP:-$CLUSTER_A_APP_DIRECT}"
        lb_mgmt="${CLUSTER_A_LB_MGMT:-$CLUSTER_A_MGMT}"
        cluster_id="a"
        # Direct per-node mgmt ports = 5151..5155 (node-1..node-5).
        # MGMT_PORT+i convention resolves node-{i+1} → 5151+i. Cluster reach
        # via lib/common.sh _refresh_mgmt_entry_point probing this range.
        node_base="5151"
    else
        cluster_endpoint="$CLUSTER_B_MGMT"
        lb_app="${CLUSTER_B_LB_APP:-$CLUSTER_B_APP_DIRECT}"
        lb_mgmt="${CLUSTER_B_LB_MGMT:-$CLUSTER_B_MGMT}"
        cluster_id="b"
        # Direct per-node mgmt ports = 5161..5165 (node-1..node-5).
        # Killing node-1 leaves 5161 dead; the harness rotates MGMT_ENTRY_POINT
        # to a surviving core via lib/common.sh _refresh_mgmt_entry_point
        # (invoked from wait_for_cluster_ready and api_get/api_post).
        node_base="5161"
    fi

    # Export for the suite scripts
    export CLUSTER_ENDPOINT="$lb_mgmt"
    export APP_ENDPOINT="$lb_app"
    export DIRECT_ENDPOINT="$cluster_endpoint"
    export MGMT_PORT="$node_base"
    export MGMT_ENTRY_POINT="$cluster_endpoint"
    export CLUSTER_ID="$cluster_id"
    export CLUSTER_NAME="aether-${cluster_id}-node-"
    # Bootstrap state directory name — must match what `aether cluster bootstrap --cluster <name>`
    # registered (i.e. CLUSTER_A_NAME/CLUSTER_B_NAME, which override [cluster].name from the TOML).
    # Read by cloud_public_ip / cloud_ssh to look up VM public IPs after `aether cluster bootstrap`.
    # Distinct from CLUSTER_NAME (a docker container-name prefix used by cluster.sh).
    if [ "$target_cluster" = "a" ]; then
        export BOOTSTRAP_CLUSTER_NAME="$CLUSTER_A_NAME"
    else
        export BOOTSTRAP_CLUSTER_NAME="$CLUSTER_B_NAME"
    fi
    # aether_failover reads global LB_MGMT_ENDPOINT — must point at THIS cluster, not whichever
    # was discovered last. Otherwise suites on cluster A send CLI traffic to cluster B's LB.
    export LB_MGMT_ENDPOINT="$lb_mgmt"
    export LB_APP_ENDPOINT="$lb_app"

    # Run suite
    local start_time
    start_time=$(date +%s)
    log_info "============================================"
    log_info "  SUITE: ${suite_name} (cluster ${target_cluster})"
    log_info "============================================"

    # Stamp SUITE_TAG so every log line from this suite's children is attributable
    # to a specific suite — essential when MAX_PARALLEL > 1 for cluster A, where
    # multiple suites' stdout interleaves. Combined with TEST_TAG (set by run_test
    # in lib/common.sh) the format is `[suite-name/test_name]`.
    export SUITE_TAG="$suite_name"
    local suite_pass=0 suite_fail=0
    for test_file in "$suite_dir"/test-*.sh; do
        [ -f "$test_file" ] || continue
        log_info "--- $(basename "$test_file") ---"
        if bash "$test_file"; then
            suite_pass=$((suite_pass + 1))
        else
            suite_fail=$((suite_fail + 1))
        fi
    done
    unset SUITE_TAG

    local duration=$(( $(date +%s) - start_time ))
    local status="passed"
    [ "$suite_fail" -gt 0 ] && status="failed"

    echo "{\"suite\":\"${suite_name}\",\"status\":\"${status}\",\"pass\":${suite_pass},\"fail\":${suite_fail},\"duration\":${duration}}" >> "$RESULTS_FILE"

    log_info "${suite_name}: ${suite_pass} passed, ${suite_fail} failed (${duration}s)"

    [ "$suite_fail" -eq 0 ]
}

# ---------------------------------------------------------------------------
# Parallel execution for Cluster A (requires bash 4.3+ for wait -n)
# ---------------------------------------------------------------------------
run_cluster_a_suites() {
    local suites=("$@")
    local max_parallel="${MAX_PARALLEL:-4}"
    local pids=()
    local failed=false

    # Check if wait -n is available (bash 4.3+)
    local has_wait_n=false
    if (wait -n 2>/dev/null; true) 2>/dev/null; then
        has_wait_n=true
    fi

    for suite in "${suites[@]}"; do
        if [ "$has_wait_n" = true ]; then
            # Wait if at max parallel
            while [ ${#pids[@]} -ge $max_parallel ]; do
                wait -n "${pids[@]}" 2>/dev/null || true
                # Prune completed pids
                local new_pids=()
                for p in "${pids[@]}"; do
                    if kill -0 "$p" 2>/dev/null; then
                        new_pids+=("$p")
                    fi
                done
                pids=("${new_pids[@]+${new_pids[@]}}")
            done
        else
            # Fallback: wait for all if at max, then clear
            if [ ${#pids[@]} -ge $max_parallel ]; then
                for p in "${pids[@]}"; do
                    wait "$p" 2>/dev/null || failed=true
                done
                pids=()
            fi
        fi

        run_suite "$suite" "a" &
        pids+=($!)
    done

    # Wait for all remaining
    for p in "${pids[@]}"; do
        wait "$p" 2>/dev/null || failed=true
    done

    [ "$failed" = false ]
}

# ---------------------------------------------------------------------------
# Sequential execution for Cluster B with self-heal
# ---------------------------------------------------------------------------
run_cluster_b_suites() {
    local suites=("$@")
    local aborted=false

    for suite in "${suites[@]}"; do
        if [ "$aborted" = true ]; then
            local dir
            dir=$(resolve_suite_dir "$suite")
            local name
            name=$(basename "${dir:-${suite}-unknown}")
            # Distinct status from a benign capability-skip (run_suite's "skipped"):
            # this suite was NOT run because cluster B is unrecoverable. print_results
            # tallies "skipped-unrecoverable" separately and FAILS the run (non-zero
            # exit) so a hard-aborted sweep can never be misread as a clean pass.
            log_warn "SKIP (unrecoverable): ${name} — cluster B did not recover; suite hard-aborted, not run"
            echo "{\"suite\":\"${name}\",\"status\":\"skipped-unrecoverable\",\"pass\":0,\"fail\":0,\"duration\":0}" >> "$RESULTS_FILE"
            continue
        fi

        run_suite "$suite" "b" || true

        # Harness-level baseline restore + unrecoverability gate (Tier B4).
        #
        # The per-test `trap cleanup EXIT` already calls restore_cluster_baseline, but
        # that trap can be omitted by a new test or fail mid-way; the loop repeats the
        # (idempotent) restore as an authoritative backstop and — crucially — uses its
        # outcome to decide whether the NEXT destructive suite may run.
        #
        # We still do NOT abort on transient churn: restore_cluster_baseline already
        # tolerates lagging READY convergence (its step 5b is soft) and spends its full
        # budget (<=600s presence + <=300s READY + <=180s quiesce, and it also runs the
        # quiesce + phase=NORMAL barriers that used to live inline here). We abort ONLY
        # when, after that budget, the cluster is GENUINELY degraded — restore hard-failed,
        # OR it returned OK but the cluster still cannot muster a quorum-safe count of READY
        # cores and a committed leader. In that state every remaining suite fails for this
        # one root cause and its results are misattributed (observed 2026-06-15: a stuck
        # 2-of-5-READY baseline silently failed 03/05/12/13). Quarantine the rest:
        # skip-with-reason instead of run-and-misblame.
        export CLUSTER_ENDPOINT="$CLUSTER_B_MGMT"
        local quiesce_start
        quiesce_start=$(date +%s)
        local _restore_rc=0
        restore_cluster_baseline || _restore_rc=$?
        local _ready _leader
        # Read liveness via the curl/api_get path (NOT the aether CLI): a reliability
        # gate must not false-quarantine when the CLI is unavailable for reasons
        # unrelated to cluster health (e.g. macOS Local Network Privacy blocking the
        # java CLI from a LAN cluster, where curl still works). restore_cluster_baseline's
        # own hard barriers are already curl-based.
        _ready=$(ready_core_count_http)
        _leader=$(cluster_leader_http || true)
        # WHOLENESS is determined by restore_cluster_baseline's terminal gate (leader
        # deficit=0 — the lag-free authority), NOT by ready_core_count. On cloud the READY
        # lifecycle query (aether nodes lifecycle --state READY) routinely reads N-2 for
        # MINUTES on a genuinely-whole cluster (deficit=0, all members present, breaker
        # untripped) — the SWIM-fed lifecycle projection lags the leader's membership view.
        # The old `_ready < _floor` condition therefore SILENTLY FALSE-QUARANTINED the chain
        # (observed 2026-06-20 on cloud: suite 13 passed, restore_rc=0, deficit=0, poller
        # showed actualCoreCount=5 throughout — yet ready=3/5 skipped 12/03/02). Quarantine
        # only on a real restore failure (restore_rc!=0 — its deficit gate caught a genuine
        # missing core) or no committed leader. `_ready` is retained for log context only.
        if [ "$_restore_rc" -ne 0 ] || [ -z "$_leader" ]; then
            log_error "Cluster B unrecoverable after suite ${suite} (restore_rc=${_restore_rc}, leader='${_leader}', ready=${_ready:-0}/${NODE_COUNT} [informational; gate=restore deficit]) — remaining destructive suites will be SKIPPED to avoid misattributed cascade failures"
            aborted=true
        fi
        local quiesce_elapsed=$(( $(date +%s) - quiesce_start ))
        printf 'quiesce_after_%s=%s\n' "$suite" "$quiesce_elapsed" >> "$TIMINGS_FILE"
    done
}

# ---------------------------------------------------------------------------
# Deploy clusters (docker env)
# ---------------------------------------------------------------------------
# Rebuild aether-node:local on the remote host from the locally-built jar.
# Without this, restarting compose pulls a stale image and code changes never reach the cluster.
rebuild_remote_node_image() {
    local host="$1"
    local jar="${REPO_ROOT}/node/target/aether-node.jar"
    local dockerfile="${REPO_ROOT}/docker/aether-node/Dockerfile"
    local config="${REPO_ROOT}/docker/aether-node/aether.toml"
    if [ ! -f "$jar" ]; then
        log_error "Local aether-node.jar not found at $jar — run build.sh first"
        return 1
    fi
    log_step "Pushing aether-node.jar to ${host} and rebuilding aether-node:local"
    remote_exec "mkdir -p ~/aether-build/node/target ~/aether-build/docker/aether-node"
    remote_scp "$jar"        "~/aether-build/node/target/aether-node.jar"
    remote_scp "$dockerfile" "~/aether-build/docker/aether-node/Dockerfile"
    remote_scp "$config"     "~/aether-build/docker/aether-node/aether.toml"
    # --no-cache prevents BuildKit from reusing a stale jar layer when bytes appear
    # equivalent (build-info.properties may be regenerated on a different cadence than
    # bytecode, so layer-hash collisions ship outdated images into the cluster).
    remote_exec "cd ~/aether-build && docker build --no-cache -q -f docker/aether-node/Dockerfile -t aether-node:local . 2>&1 | tail -5"
}

# ---------------------------------------------------------------------------
# CLI / node-image version-parity preflight (#440)
# ---------------------------------------------------------------------------
# Incident: the gate silently resolved a STALE rc1 `aether` from ~/.aether/bin
# (PATH fallback) and ran it against a freshly-built rc2 node image — every node
# aborted boot on the version mismatch and a full provision cycle burned before
# anyone noticed. Assert BEFORE any `aether cluster bootstrap` that the `aether`
# CLI in use reports the SAME Implementation-Version as the node artifact this run
# deploys (node/target/aether-node.jar — the jar baked into aether-node:local and
# the reference version for the cloud jar_url). Loud-abort on mismatch.
#   AETHER_BIN=/path/to/aether  pins an explicit freshly-built CLI (still checked);
#   its directory is prepended to PATH so every downstream bare-`aether` call
#   (common.sh aether_failover, `aether cluster bootstrap`, suite subshells) uses it.
version_parity_preflight() {
    if [ -n "${AETHER_BIN:-}" ]; then
        if [ ! -x "$AETHER_BIN" ]; then
            log_error "version-parity preflight: AETHER_BIN='${AETHER_BIN}' is not an executable file"
            return 1
        fi
        local _bin_dir
        _bin_dir="$(cd "$(dirname "$AETHER_BIN")" && pwd)"
        PATH="${_bin_dir}:${PATH}"
        export PATH
    fi

    # Banner: always log WHICH binary is in use (post-PATH-repin) so the next
    # PATH surprise is visible in any captured log.
    local resolved
    resolved="$(command -v aether || true)"
    log_info "version-parity preflight: aether CLI = ${resolved:-<none on PATH>} (AETHER_BIN=${AETHER_BIN:-<unset>})"
    if [ -z "$resolved" ]; then
        log_error "version-parity preflight: no 'aether' CLI on PATH (and AETHER_BIN unset/invalid) — cannot bootstrap"
        return 1
    fi

    # Expected version = the node artifact this run deploys.
    local node_jar="${REPO_ROOT}/node/target/aether-node.jar"
    if [ ! -f "$node_jar" ]; then
        log_warn "version-parity preflight: node jar not found at ${node_jar} — skipping parity check (run build.sh to enable it)"
        return 0
    fi
    local manifest node_version
    manifest="$(unzip -p "$node_jar" META-INF/MANIFEST.MF 2>/dev/null | tr -d '\r')" || true
    node_version="$(printf '%s\n' "$manifest" | sed -n 's/^Implementation-Version:[[:space:]]*//p' | sed -n '1p')"
    if [ -z "$node_version" ]; then
        log_warn "version-parity preflight: could not read Implementation-Version from ${node_jar} — skipping parity check"
        return 0
    fi

    # CLI version: `aether --version` -> "Aether <version> (built <date>)".
    local cli_raw cli_version
    cli_raw="$(aether --version 2>/dev/null)" || true
    cli_version="$(printf '%s\n' "$cli_raw" | sed -n 's/^Aether[[:space:]]\{1,\}\([^[:space:]]\{1,\}\).*/\1/p' | sed -n '1p')"
    if [ -z "$cli_version" ]; then
        log_error "version-parity preflight: could not parse a version from 'aether --version' (got: '${cli_raw}') via ${resolved}"
        return 1
    fi

    log_info "version-parity preflight: CLI=${cli_version}  node-image=${node_version}  (jar ${node_jar})"
    if [ "$cli_version" != "$node_version" ]; then
        log_error "version-parity preflight: CLI/node-image VERSION MISMATCH — 'aether' reports '${cli_version}' but the node artifact this run deploys is '${node_version}'."
        log_error "  aether CLI in use: ${resolved}"
        log_error "  Pin the freshly-built CLI with AETHER_BIN=/path/to/aether, or fix PATH so a stale ~/.aether/bin does not shadow it."
        log_error "  Aborting BEFORE bootstrap: a stale CLI against a mismatched node image aborts every node's boot and burns a full provision cycle (#440)."
        return 1
    fi
    log_pass "version-parity preflight: CLI and node image agree on ${cli_version}"
    return 0
}

deploy_docker() {
    local host="${TARGET_HOST:-localhost}"

    # Localhost twin of `cleanup_cluster_zombies` (lib/cluster.sh). Same semantics,
    # direct `docker` invocation since remote_exec always SSHs.
    _local_cleanup_zombies() {
        local cid="$1"
        local allowlist="aether-${cid}-node-1|aether-${cid}-node-2|aether-${cid}-node-3|aether-${cid}-node-4|aether-${cid}-node-5|aether-${cid}-mgmt-gateway|forge-postgres"
        local names
        names=$(docker ps -a --filter "label=aether.cluster=${cid}" --format '{{.Names}}' 2>/dev/null | grep -Ev "^(${allowlist})$" || true)
        if [ -z "$names" ]; then
            log_info "cleanup_cluster_zombies(${cid}): no zombies"
            return 0
        fi
        local z
        while IFS= read -r z; do
            [ -z "$z" ] && continue
            log_info "cleanup_cluster_zombies(${cid}): removing zombie ${z}"
            docker rm -f "$z" >/dev/null 2>&1 || log_warn "cleanup_cluster_zombies(${cid}): docker rm -f ${z} failed"
        done <<< "$names"
        return 0
    }

    if [ "$host" != "localhost" ] && [ "$SKIP_IMAGE_PUSH" = false ]; then
        rebuild_remote_node_image "$host"
    elif [ "$host" != "localhost" ]; then
        log_info "Skipping image push (--skip-image-push); reusing existing aether-node:local on ${host}"
    fi

    log_step "Deploying Cluster A (non-destructive)"
    # CRITICAL: drop persisted state volumes BEFORE compose up. The aether_pgdata
    # volume holds the consensus snapshot and KV-store; if it survives between runs
    # (it does — `down -v` doesn't touch externally-named volumes), fresh containers
    # replay phantom peers from prior CTM-provisioned ON_DUTY entries — observed as
    # `coreCount=37` with only 5 containers running. Also wipe ad-hoc CTM-provisioned
    # `aether-core-node-*` containers from prior runs that compose doesn't manage.
    if [ "$host" = "localhost" ]; then
        docker compose -f "$COMPOSE_A" down -v 2>/dev/null || true
        # CTM-provisioned containers share the canonical core prefix aether-<cluster>-node-
        # (NodeId == container_name; NO -core-/pool segment). Name-prefix sweep is a
        # redundant backup to the authoritative label sweep below; cover cluster A + default.
        docker rm -f $(docker ps -aq --filter "name=aether-a-node-") 2>/dev/null || true
        docker rm -f $(docker ps -aq --filter "name=aether-default-node-") 2>/dev/null || true
        docker rm -f $(docker ps -aq --filter "name=aether-a-node-") 2>/dev/null || true
        # Label-scoped zombie sweep (catches any CTM container missed by the name-prefix
        # filters above, e.g. shapes introduced by future provider/pool naming changes).
        _local_cleanup_zombies "a"
        docker volume rm -f aether_pgdata 2>/dev/null || true
        docker compose -f "$COMPOSE_A" up -d 2>&1 | tail -5
    else
        remote_scp "$COMPOSE_A" "~/docker-compose-a.yml"
        remote_exec "cd ~ && docker compose -f docker-compose-a.yml down -v 2>/dev/null || true; docker rm -f \$(docker ps -aq --filter name=aether-a-node-) 2>/dev/null || true; docker rm -f \$(docker ps -aq --filter name=aether-default-node-) 2>/dev/null || true; docker volume rm -f aether_pgdata 2>/dev/null || true"
        cleanup_cluster_zombies "a"
        remote_exec "cd ~ && docker compose -f docker-compose-a.yml up -d 2>&1 | tail -5"
    fi

    # Stagger: give Cluster A an uninterrupted formation window before Cluster B's
    # 5 JVMs co-boot. deploy_docker brings up both clusters back-to-back; with all
    # 10 nodes booting at once against the shared docker socket, Cluster A's formation
    # was starved and never reached 5-ready (observed on remote: A converges in ~28s
    # ALONE but timed out at 957s when B co-booted). Non-fatal — if A has not formed
    # within the window, deploy B anyway and let the Step-3 gate surface it.
    log_step "Staggering: waiting for Cluster A to form before deploying Cluster B"
    wait_for_node_count_on "$CLUSTER_A_MGMT" 5 180 || log_warn "Cluster A not fully formed within stagger window — deploying Cluster B anyway"

    log_step "Deploying Cluster B (destructive)"
    if [ "$host" = "localhost" ]; then
        docker compose -f "$COMPOSE_B" down -v 2>/dev/null || true
        _local_cleanup_zombies "b"
        docker compose -f "$COMPOSE_B" up -d 2>&1 | tail -5
    else
        remote_scp "$COMPOSE_B" "~/docker-compose-b.yml"
        remote_exec "cd ~ && docker rm -f \$(docker ps -aq --filter name=aether-b-node-) 2>/dev/null; docker rm -f \$(docker ps -aq --filter name=aether-default-node-) 2>/dev/null || true; docker compose -f docker-compose-b.yml down -v 2>/dev/null || true"
        cleanup_cluster_zombies "b"
        remote_exec "cd ~ && docker compose -f docker-compose-b.yml up -d 2>&1 | tail -5"
    fi
}

# ---------------------------------------------------------------------------
# Cloud per-cluster bootstrap (env=cloud only)
# ---------------------------------------------------------------------------
# CLOUD_TOML_A / CLOUD_TOML_B must already be resolved (and exported — #441
# S20) by the Step-2 cloud branch before either function runs.
bootstrap_cloud_cluster_a() {
    aether cluster bootstrap "$CLOUD_TOML_A" --cluster "$CLUSTER_A_NAME" --yes --wait --timeout 300
    # Cloud override: derive endpoints from the freshly-provisioned VM's public IP.
    # Default CLUSTER_A_MGMT/APP point at docker-compose host-mapped ports (5150/8070),
    # which don't exist on Hetzner VMs (mgmt=8080, app=8070 per cloud-hetzner.toml).
    local cluster_a_ip
    cluster_a_ip=$(BOOTSTRAP_CLUSTER_NAME="$CLUSTER_A_NAME" CLOUD_SOURCE_NAME="hetzner-eu" cloud_public_ip node-1)
    if [ -n "$cluster_a_ip" ]; then
        CLUSTER_A_MGMT="http://${cluster_a_ip}:8080"
        CLUSTER_A_APP_DIRECT="http://${cluster_a_ip}:8070"
        log_info "Cluster A endpoints: mgmt=${CLUSTER_A_MGMT} app=${CLUSTER_A_APP_DIRECT}"
    else
        log_warn "Could not resolve Cluster A public IP; falling back to default ${CLUSTER_A_MGMT}"
    fi
    # Test scripts hit both mgmt and app HTTP with the static `aether-integration-test-key`.
    # Cloud TOML configures it under `[app-http] api_keys` (consulted by both validators).
    # The cluster's bootstrap-generated key still lives in KV-Store + the registry file
    # (used by `aether cluster *` CLI commands via ClusterRegistry); the two paths are
    # decoupled.
    log_info "Cluster A: tests use static api key; CLI uses ${HOME}/.aether/clusters/${CLUSTER_A_NAME}/api-key"
}

bootstrap_cloud_cluster_b() {
    aether cluster bootstrap "$CLOUD_TOML_B" --cluster "$CLUSTER_B_NAME" --yes --wait --timeout 300
    # Cloud override: derive endpoints from the freshly-provisioned VM's public IP.
    local cluster_b_ip
    cluster_b_ip=$(BOOTSTRAP_CLUSTER_NAME="$CLUSTER_B_NAME" CLOUD_SOURCE_NAME="hetzner-eu" cloud_public_ip node-1)
    if [ -n "$cluster_b_ip" ]; then
        CLUSTER_B_MGMT="http://${cluster_b_ip}:8080"
        CLUSTER_B_APP_DIRECT="http://${cluster_b_ip}:8070"
        log_info "Cluster B endpoints: mgmt=${CLUSTER_B_MGMT} app=${CLUSTER_B_APP_DIRECT}"
    else
        log_warn "Could not resolve Cluster B public IP; falling back to default ${CLUSTER_B_MGMT}"
    fi
    log_info "Cluster B: tests use static api key; CLI uses ${HOME}/.aether/clusters/${CLUSTER_B_NAME}/api-key"
}

# ---------------------------------------------------------------------------
# Cloud-only serialized Cluster B bring-up (Step 9.5)
# ---------------------------------------------------------------------------
# Mirrors, in order, exactly what the main flow does for cluster B on
# docker/remote: Step 2 (bootstrap), Step 3 (wait + quiesce), Step 4 (endpoint
# discovery), Step 7 (blueprints), Step 7.5 (connectivity preflight). On cloud
# it runs only AFTER cluster A's suites completed and A's VMs were reaped, so
# the two clusters never coexist (see the Step-2 serialization rationale).
# detect_capabilities (Step 5) is deliberately NOT re-run: it probes env type +
# PG-VM reachability only (lib/suite.sh), not cluster state, so its Step-5
# result remains valid for cluster B.
cloud_bringup_cluster_b() {
    # Step 2 analog
    if [ "$SKIP_DEPLOY" = false ]; then
        log_step "Bootstrapping cloud Cluster B (runtime=${CLOUD_RUNTIME})"
        bootstrap_cloud_cluster_b
    fi

    # Step 3 analog
    log_step "Waiting for Cluster B"
    wait_for_node_count_on "$CLUSTER_B_MGMT" 5 180
    wait_for_leader_on "$CLUSTER_B_MGMT" 60
    await_generation_quiesced "$CLUSTER_B_MGMT" "current" 60 || log_warn "Cluster B snapshot not quiesced yet"

    # Step 4 analog
    log_step "Discovering Cluster B LB endpoints"
    discover_endpoints "$CLUSTER_B_MGMT"
    CLUSTER_B_LB_APP="${LB_APP_ENDPOINT}"
    CLUSTER_B_LB_MGMT="${LB_MGMT_ENDPOINT}"
    log_info "Cluster B: app=${CLUSTER_B_LB_APP} mgmt=${CLUSTER_B_LB_MGMT}"

    # Step 7 analog
    if [ "$SKIP_DEPLOY" = false ]; then
        log_step "Deploying blueprints to Cluster B"
        B_BLUEPRINTS=($(collect_blueprints "${B_SUITES[@]}"))
        [ ${#B_BLUEPRINTS[@]} -gt 0 ] && deploy_blueprints "$CLUSTER_B_LB_MGMT" "${B_BLUEPRINTS[@]}"
        await_generation_quiesced "$CLUSTER_B_LB_MGMT" "current+1" 60 || \
            log_warn "Cluster B did not quiesce after blueprint deploy"
    fi

    # Step 7.5 analog — same preserve-don't-teardown handling as the main
    # Step-7.5 block (see the rationale comments there): the verdict means the
    # cluster is healthy and only THIS machine's CLI is blocked.
    log_step "Connectivity preflight for Cluster B (CLI vs curl reachability)"
    if ! connectivity_preflight "$CLUSTER_B_MGMT" "Cluster B"; then
        log_error "Connectivity preflight verdict: raw HTTP reaches Cluster B but the 'aether' CLI does not."
        log_error "Aborting before any Cluster B suite runs — fix CLI/network access on this machine (see preflight message above) and re-run."
        SKIP_TEARDOWN=true
        log_error "Cluster B PRESERVED (not torn down): it is healthy and reachable via curl; only this machine's CLI is blocked."
        exit 2
    fi
}

# ---------------------------------------------------------------------------
# Teardown clusters
# ---------------------------------------------------------------------------
teardown() {
    log_step "Tearing down clusters"
    # Clean up snapshot-override temp TOMLs (set when AETHER_VM_SNAPSHOT_ID is used).
    if [ -n "${CLOUD_TOML_TMPDIR:-}" ] && [ -d "$CLOUD_TOML_TMPDIR" ]; then
        rm -rf "$CLOUD_TOML_TMPDIR"
    fi
    case "$ENV_TYPE" in
        docker|remote)
            local host="${TARGET_HOST:-localhost}"
            if [ "$host" = "localhost" ]; then
                # CTM containers first — they hold the network; compose down would stall otherwise.
                # Match the canonical aether-<cluster>-node- prefix (covers seeds + CTM replacements).
                docker rm -f $(docker ps -aq --filter "name=aether-a-node-" --filter "name=aether-b-node-") 2>/dev/null || true
                docker compose -f "$COMPOSE_A" down -v 2>/dev/null || true
                docker compose -f "$COMPOSE_B" down -v 2>/dev/null || true
            else
                # Same order on remote: sweep CTM containers before compose down
                remote_exec "docker rm -f \$(docker ps -aq --filter name=aether-a-node-) 2>/dev/null; docker rm -f \$(docker ps -aq --filter name=aether-b-node-) 2>/dev/null; docker rm -f \$(docker ps -aq --filter name=aether-default-node-) 2>/dev/null || true"
                remote_exec "docker compose -f ~/docker-compose-a.yml down -v 2>/dev/null || true"
                remote_exec "docker compose -f ~/docker-compose-b.yml down -v 2>/dev/null || true"
            fi
            ;;
        cloud)
            # `aether cluster destroy` has no --cluster flag (only operates on the active cluster).
            # Use cloud-reaper.sh which filters by `aether-cluster` label — works regardless of
            # bootstrap-state.json existence, idempotent, exits 0 if nothing to destroy.
            # A's guard uses the PRE-GATE snapshot (A_SUITES_SELECTED): the Step-8
            # gate removes 00 from A_SUITES after running it, so on a `--suites 00`
            # run the array is empty here even though cluster A was bootstrapped.
            # On the serialized cloud flow A is normally already reaped in Step 9.5
            # — this reap then finds nothing and exits 0 (idempotent by design).
            [ "${A_SUITES_SELECTED:-0}" -gt 0 ] && ("${REPO_ROOT}/../tools/cloud-reaper.sh" --cluster "$CLUSTER_A_NAME" --destroy --force 2>&1 | tail -3 || true)
            [ ${#B_SUITES[@]} -gt 0 ] && ("${REPO_ROOT}/../tools/cloud-reaper.sh" --cluster "$CLUSTER_B_NAME" --destroy --force 2>&1 | tail -3 || true)
            # Catch-all sweep for CTM-provisioned ORPHANS. The scoped `--cluster <name>`
            # reaps above filter on `aether-cluster=<name>` (plus same-cluster orphans),
            # but CTM-provisioned replacement VMs may carry a DIFFERENT or MISSING
            # `aether-cluster` label value (the seed/replacement prefix mismatch:
            # cluster reports `aether-cloud-test-b-node-<ULID>` while the VM is labeled
            # `aether-node-id=aether-b-node-<ULID>` with no matching `aether-cluster`).
            # Those rows are dropped by the per-cluster orphan filter and survived
            # teardown last run (4 orphan VMs leaked). A final bare reaper run (no
            # --cluster) matches ANY `aether-cluster` OR `aether-node-id` label and
            # closes the gap. Only fired when cloud resources were ACTUALLY provisioned
            # this run.
            #
            # 2026-08-03 — TWO corrections after this deleted the standing `test-pg` VM:
            #
            #   1. The previous guard tested SUITES SELECTED, not resources provisioned, so
            #      a run that died during bootstrap (unresolvable ${env:PG_*} secrets, 15s in,
            #      zero VMs created) still reached this line. It now gates on
            #      CLOUD_RESOURCES_PROVISIONED, set only after a bootstrap call returns.
            #
            #   2. The previous comment claimed "Safe here: the integration run owns every
            #      aether-labeled resource in the account." That premise was FALSE. `test-pg`
            #      is aether-labeled, long-lived, and owned by no run. cloud-reaper.sh now
            #      protects it by default (PROTECTED_CLUSTERS), so this bare reap can no
            #      longer take it even if the guard above is wrong again — the tool enforces
            #      it rather than every caller having to remember.
            if [ "${CLOUD_RESOURCES_PROVISIONED:-false}" = true ]; then
                ("${REPO_ROOT}/../tools/cloud-reaper.sh" --destroy --force 2>&1 | tail -3 || true)
            fi
            # Re-close PG firewall (5432 → denied) after cluster teardown.
            "${REPO_ROOT}/../tools/pg-firewall.sh" close 2>&1 | tail -1 || true
            ;;
    esac
}

# ---------------------------------------------------------------------------
# Print per-phase and per-quiesce-barrier timings (issue #174 Part 2)
# ---------------------------------------------------------------------------
print_timing_report() {
    [ -s "$TIMINGS_FILE" ] || return 0
    echo ""
    echo "========================================"
    echo "  TIMING SUMMARY"
    echo "========================================"
    local provisioning=0 formation=0 blueprint=0
    provisioning=$(awk -F= '/^provisioning=/{print $2; exit}' "$TIMINGS_FILE")
    formation=$(awk -F= '/^cluster_formation=/{print $2; exit}' "$TIMINGS_FILE")
    blueprint=$(awk -F= '/^blueprint_deploy=/{print $2; exit}' "$TIMINGS_FILE")
    printf "  Provisioning:        %ss\n" "${provisioning:-n/a}"
    printf "  Cluster formation:   %ss\n" "${formation:-n/a}"
    printf "  Blueprint deploy:    %ss\n" "${blueprint:-n/a}"
    # Emit per-suite quiesce barriers (ms) and await-quiesced call durations (ms) if present.
    local quiesce_lines
    quiesce_lines=$(grep -E '^(quiesce_after_|await_)' "$TIMINGS_FILE" 2>/dev/null || true)
    if [ -n "$quiesce_lines" ]; then
        echo "  Quiesce barriers:"
        while IFS='=' read -r key value; do
            [ -z "$key" ] && continue
            printf "    %-32s %s\n" "${key}:" "$value"
        done <<< "$quiesce_lines"
    fi
    echo "========================================"
}

# ---------------------------------------------------------------------------
# Print results report
# ---------------------------------------------------------------------------
print_results() {
    local results_file="$1"
    echo ""
    echo "========================================"
    echo "  INTEGRATION TEST RESULTS"
    echo "========================================"

    local total=0 passed=0 failed=0 skipped=0 unrecoverable=0
    while IFS= read -r line; do
        [ -z "$line" ] && continue
        local suite status pass fail dur
        suite=$(json_value "$line" "suite")
        status=$(json_value "$line" "status")
        pass=$(json_value "$line" "pass")
        fail=$(json_value "$line" "fail")
        dur=$(json_value "$line" "duration")

        total=$((total + 1))
        case "$status" in
            passed)  passed=$((passed + 1)); printf "  [PASS] %-25s %3dp/%df  (%ds)\n" "$suite" "$pass" "$fail" "$dur" ;;
            failed)  failed=$((failed + 1)); printf "  [FAIL] %-25s %3dp/%df  (%ds)\n" "$suite" "$pass" "$fail" "$dur" ;;
            skipped) skipped=$((skipped + 1)); printf "  [SKIP] %-25s\n" "$suite" ;;
            skipped-unrecoverable) unrecoverable=$((unrecoverable + 1)); printf "  [ABORT] %-25s (not run — cluster B unrecoverable)\n" "$suite" ;;
        esac
    done < "$results_file"

    echo "========================================"
    printf "  Total: %d | Passed: %d | Failed: %d | Skipped: %d | Unrecoverable: %d\n" \
        "$total" "$passed" "$failed" "$skipped" "$unrecoverable"
    echo "========================================"

    print_timing_report

    # Generate JSON report
    echo "[" > "$RESULTS_JSON"
    local first=true
    while IFS= read -r line; do
        [ -z "$line" ] && continue
        if [ "$first" = true ]; then
            first=false
        else
            echo "," >> "$RESULTS_JSON"
        fi
        printf "  %s" "$line" >> "$RESULTS_JSON"
    done < "$results_file"
    echo "" >> "$RESULTS_JSON"
    echo "]" >> "$RESULTS_JSON"
    log_info "JSON report: ${RESULTS_JSON}"

    # Non-zero exit on ANY real failure OR a hard-abort: a run that quarantined
    # remaining suites because cluster B never recovered is NOT a pass, even when
    # zero suites reported test failures. "Unrecoverable" is tallied above and gates
    # the exit here so the outcome is visible in both the summary and the exit code.
    [ "$failed" -eq 0 ] && [ "$unrecoverable" -eq 0 ]
}

# ===========================================================================
# MAIN
# ===========================================================================
log_info "Integration test runner -- env=${ENV_TYPE}"
START_TIME=$(date +%s)

# --- Step 0: Lint integration test infra (always; cheap; protects against
#     test-side regressions even when --skip-build is used) ---
log_step "Lint integration tests"
"${SCRIPT_DIR}/lint-tests.sh"
"${SCRIPT_DIR}/lib/contract-test.sh"

# --- Step 1: Build ---
if [ "$SKIP_BUILD" = false ] && [ -x "${REPO_ROOT}/build.sh" ]; then
    log_step "Building project"
    "${REPO_ROOT}/build.sh"
fi

# --- Compute selected suites early so cluster bootstrap can be skipped per-cluster ---
A_SUITES=($(filter_suites "${CLUSTER_A_SUITES[@]}"))
B_SUITES=($(filter_suites "${CLUSTER_B_SUITES[@]}"))
# Snapshot A's original selection count NOW: the Step-8 00-smoke gate mutates
# A_SUITES (removes 00 once run), but the cloud-serialized flow (Step 9.5 reap)
# and the cloud teardown branch must know whether cluster A was in play at all
# — on a `--suites 00` cloud run the post-gate A_SUITES is empty even though
# cluster A was bootstrapped and must still be reaped.
A_SUITES_SELECTED=${#A_SUITES[@]}

# Install EXIT trap so teardown runs even when later steps fail (set -e exit, errors,
# unbound variables). Without this, any failure between Step 2 and Step 11 leaks
# bootstrapped clusters — on cloud, that is real €/hour cost.
trap '[ "$SKIP_TEARDOWN" = false ] && teardown' EXIT

# Open the Hetzner firewall guarding the PG VM for the duration of the cloud test
# window. Closed again by teardown(). Skipped on docker/remote (those use the
# in-cluster forge-postgres container, not the shared Hetzner PG VM).
if [ "$ENV_TYPE" = "cloud" ]; then
    "${REPO_ROOT}/../tools/pg-firewall.sh" open 2>&1 | tail -1
fi

# --- Step 1.5: CLI / node-image version-parity preflight (#440) ---
# Runs BEFORE any provisioning / `aether cluster bootstrap`: a version-mismatched
# CLI would otherwise abort every node's boot and burn a full provision cycle.
log_step "CLI/node-image version-parity preflight"
if ! version_parity_preflight; then
    log_error "Aborting before cluster bootstrap — CLI/node-image version parity check failed (see above)."
    exit 3
fi

# --- Step 2: Deploy clusters ---
PROVISION_START=$(date +%s)
if [ "$SKIP_DEPLOY" = false ]; then
    log_step "Deploying clusters"
    case "$ENV_TYPE" in
        docker|remote) deploy_docker ;;
        cloud)
            log_step "Bootstrapping cloud clusters (runtime=${CLOUD_RUNTIME})"
            case "$CLOUD_RUNTIME" in
                jvm)
                    CLOUD_TOML_A="${SCRIPT_DIR}/env/cloud-hetzner-jvm.toml"
                    CLOUD_TOML_B="${SCRIPT_DIR}/env/cloud-hetzner-jvm-b.toml"
                    SNAPSHOT_ID_VAR="${AETHER_VM_SNAPSHOT_ID_JVM:-}"
                    ;;
                *)
                    CLOUD_TOML_A="${SCRIPT_DIR}/env/cloud-hetzner.toml"
                    CLOUD_TOML_B="${SCRIPT_DIR}/env/cloud-hetzner-b.toml"
                    SNAPSHOT_ID_VAR="${AETHER_VM_SNAPSHOT_ID:-}"
                    ;;
            esac
            # Pre-pulled snapshot override: rewrite the OS-image line to the snapshot
            # id in temp copies of the cloud TOMLs, leave the originals untouched.
            # The temp dir is cleaned by the existing teardown EXIT trap.
            # See aether/docs/operator/vm-snapshot.md.
            if [ -n "$SNAPSHOT_ID_VAR" ]; then
                log_info "Using pre-pulled VM snapshot id=${SNAPSHOT_ID_VAR}"
                CLOUD_TOML_TMPDIR=$(mktemp -d -t aether-snapshot-toml.XXXXXX)
                toml_a_tmp="${CLOUD_TOML_TMPDIR}/$(basename "$CLOUD_TOML_A")"
                toml_b_tmp="${CLOUD_TOML_TMPDIR}/$(basename "$CLOUD_TOML_B")"
                # Match only the OS-image line `image = "ubuntu-22.04"`, not the
                # runtime-block `image = "ghcr.io/..."` (registry image with slashes).
                sed -E 's|^(image[[:space:]]*=[[:space:]]*)"ubuntu-[^"]*"|\1"'"$SNAPSHOT_ID_VAR"'"|' \
                    "$CLOUD_TOML_A" > "$toml_a_tmp"
                sed -E 's|^(image[[:space:]]*=[[:space:]]*)"ubuntu-[^"]*"|\1"'"$SNAPSHOT_ID_VAR"'"|' \
                    "$CLOUD_TOML_B" > "$toml_b_tmp"
                CLOUD_TOML_A="$toml_a_tmp"
                CLOUD_TOML_B="$toml_b_tmp"
            fi
            # #441 S20: export the FINAL (possibly snapshot-rewritten) TOML paths so a
            # mid-run recovery call from inside a suite subshell (restart_all_nodes'
            # cloud full-self-drain branch, lib/cluster.sh) can re-bootstrap with the
            # SAME TOML this run used, without re-deriving CLOUD_RUNTIME/SNAPSHOT_ID_VAR
            # (neither of which is exported). CLOUD_TOML_TMPDIR (if used) is only
            # cleaned up in teardown()'s EXIT trap, so these paths stay valid for the
            # entire run.
            export CLOUD_TOML_A CLOUD_TOML_B
            if [ ${#A_SUITES[@]} -gt 0 ]; then
                bootstrap_cloud_cluster_a
                # Records that cloud resources were ACTUALLY provisioned this run, which is
                # what the teardown safety-net must gate on. See the guard below.
                CLOUD_RESOURCES_PROVISIONED=true
            else
                log_info "Skipping Cluster A bootstrap (no A-suites selected)"
            fi
            # Cloud clusters are SERIALIZED: cluster B is NOT bootstrapped here.
            # It comes up in Step 9.5 (cloud_bringup_cluster_b), after cluster
            # A's suites have run and A's VMs are reaped. Concurrent A+B
            # (~14-15 VMs incl. auto-heal churn + zombies) pins the Hetzner
            # account server limit, and every 03-scaling scale-up then fails
            # with 403 resource_limit_exceeded (deterministic across 3 runs);
            # serial peaks at ~8-10 VMs. docker/remote keep the concurrent
            # flow: compose-a owns forge-postgres + both networks cluster B
            # attaches to, so serializing there is structurally impossible —
            # and remote is green as-is.
            if [ ${#B_SUITES[@]} -gt 0 ]; then
                log_info "Cluster B bootstrap deferred until after Cluster A suites + reap (serialized cloud clusters)"
            else
                log_info "Skipping Cluster B bootstrap (no B-suites selected)"
            fi
            ;;
    esac
fi
PROVISION_ELAPSED=$(( $(date +%s) - PROVISION_START ))
printf 'provisioning=%s\n' "$PROVISION_ELAPSED" >> "$TIMINGS_FILE"

# --- Step 3: Wait for clusters ---
FORMATION_START=$(date +%s)
if [ ${#A_SUITES[@]} -gt 0 ]; then
    log_step "Waiting for Cluster A"
    wait_for_node_count_on "$CLUSTER_A_MGMT" 5 180
    wait_for_leader_on "$CLUSTER_A_MGMT" 60
fi

# On cloud, cluster B does not exist yet — its wait/discover/blueprints/
# preflight run in Step 9.5 (cloud_bringup_cluster_b) after A's suites + reap.
if [ ${#B_SUITES[@]} -gt 0 ] && [ "$ENV_TYPE" != "cloud" ]; then
    log_step "Waiting for Cluster B"
    wait_for_node_count_on "$CLUSTER_B_MGMT" 5 180
    wait_for_leader_on "$CLUSTER_B_MGMT" 60
fi

# Gate cluster readiness on ClusterGeneration quiescence — avoids racing the
# later blueprint-deploy phase against a cluster that still hasn't converged.
[ ${#A_SUITES[@]} -gt 0 ] && (await_generation_quiesced "$CLUSTER_A_MGMT" "current" 60 || log_warn "Cluster A snapshot not quiesced yet")
[ ${#B_SUITES[@]} -gt 0 ] && [ "$ENV_TYPE" != "cloud" ] && (await_generation_quiesced "$CLUSTER_B_MGMT" "current" 60 || log_warn "Cluster B snapshot not quiesced yet")

FORMATION_ELAPSED=$(( $(date +%s) - FORMATION_START ))
printf 'cluster_formation=%s\n' "$FORMATION_ELAPSED" >> "$TIMINGS_FILE"

# --- Step 4: Discover LB endpoints ---
log_step "Discovering LB endpoints"
if [ ${#A_SUITES[@]} -gt 0 ]; then
    discover_endpoints "$CLUSTER_A_MGMT"
    CLUSTER_A_LB_APP="${LB_APP_ENDPOINT}"
    CLUSTER_A_LB_MGMT="${LB_MGMT_ENDPOINT}"
    log_info "Cluster A: app=${CLUSTER_A_LB_APP} mgmt=${CLUSTER_A_LB_MGMT}"
fi

if [ ${#B_SUITES[@]} -gt 0 ] && [ "$ENV_TYPE" != "cloud" ]; then
    discover_endpoints "$CLUSTER_B_MGMT"
    CLUSTER_B_LB_APP="${LB_APP_ENDPOINT}"
    CLUSTER_B_LB_MGMT="${LB_MGMT_ENDPOINT}"
    log_info "Cluster B: app=${CLUSTER_B_LB_APP} mgmt=${CLUSTER_B_LB_MGMT}"
fi

# --- Step 5: Detect capabilities ---
detect_capabilities "$ENV_TYPE"

# --- Step 6: Filter suites ---
# A_SUITES / B_SUITES already computed above (before Step 2) so per-cluster
# bootstrap can be skipped when only one side's suites are selected.

# Step 6.5 (post-bring-up "Cleaning up ghost CTM-provisioned containers") was
# REMOVED 2026-05-19c. It was over-eager: when CTM legitimately provisions
# a replacement during cluster bring-up (e.g., a transient compose-node
# registration race), this step would `docker rm -f` it, leaving a stale
# ON_DUTY NodeLifecycleKey in KV with no live container. The label-scoped
# `cleanup_cluster_zombies` invoked from `deploy_docker` BEFORE `up -d`
# (lib/cluster.sh) is the correct replacement: it scopes to stale containers
# from prior runs (label `aether.cluster=<id>` with non-allowlisted name)
# and runs before the new compose stack starts, so it never kills a
# legitimate runtime CTM container.

# --- Step 7: Deploy blueprints ---
BLUEPRINT_START=$(date +%s)
if [ "$SKIP_DEPLOY" = false ]; then
    if [ ${#A_SUITES[@]} -gt 0 ]; then
        log_step "Deploying blueprints to Cluster A"
        A_BLUEPRINTS=($(collect_blueprints "${A_SUITES[@]}"))
        [ ${#A_BLUEPRINTS[@]} -gt 0 ] && deploy_blueprints "$CLUSTER_A_LB_MGMT" "${A_BLUEPRINTS[@]}"
        # Barrier: blueprints are ACTIVE when the next generation has quiesced.
        await_generation_quiesced "$CLUSTER_A_LB_MGMT" "current+1" 60 || \
            log_warn "Cluster A did not quiesce after blueprint deploy"
    fi

    if [ ${#B_SUITES[@]} -gt 0 ] && [ "$ENV_TYPE" != "cloud" ]; then
        log_step "Deploying blueprints to Cluster B"
        B_BLUEPRINTS=($(collect_blueprints "${B_SUITES[@]}"))
        [ ${#B_BLUEPRINTS[@]} -gt 0 ] && deploy_blueprints "$CLUSTER_B_LB_MGMT" "${B_BLUEPRINTS[@]}"
        await_generation_quiesced "$CLUSTER_B_LB_MGMT" "current+1" 60 || \
            log_warn "Cluster B did not quiesce after blueprint deploy"
    fi
fi
BLUEPRINT_ELAPSED=$(( $(date +%s) - BLUEPRINT_START ))
printf 'blueprint_deploy=%s\n' "$BLUEPRINT_ELAPSED" >> "$TIMINGS_FILE"

# --- Step 7.5: Connectivity preflight (C7) ---
# After deploy + cluster-ready, before the first suite runs: probe each selected
# cluster's management endpoint BOTH ways — raw HTTP (curl) and the `aether` CLI —
# to distinguish "cluster down" from "this operator machine's CLI can't reach the
# cluster" (macOS Local Network Privacy / proxy / IPv6). The curl-OK + CLI-fail
# verdict returns non-zero; we STOP there rather than running 00-smoke and
# misattributing the `No route to host` cascade to a dead cluster.
# See aether/docs/specs/harness-resilience-spec.md §6 C7.
log_step "Connectivity preflight (CLI vs curl reachability)"
PREFLIGHT_STOP=false
if [ ${#A_SUITES[@]} -gt 0 ]; then
    connectivity_preflight "$CLUSTER_A_MGMT" "Cluster A" || PREFLIGHT_STOP=true
fi
if [ ${#B_SUITES[@]} -gt 0 ] && [ "$ENV_TYPE" != "cloud" ]; then
    connectivity_preflight "$CLUSTER_B_MGMT" "Cluster B" || PREFLIGHT_STOP=true
fi
if [ "$PREFLIGHT_STOP" = true ]; then
    log_error "Connectivity preflight verdict: raw HTTP reaches the cluster but the 'aether' CLI does not."
    log_error "Aborting before any suite runs — the cluster is healthy; fix CLI/network access on this machine (see preflight message above) and re-run."
    # This verdict is, by construction, "cluster is healthy (curl reached it), only
    # THIS machine's CLI is blocked" — connectivity_preflight returns non-zero ONLY in
    # that case. Tearing the cluster down here would destroy a healthy cluster and force
    # a full re-bootstrap once the operator fixes Local Network access. So preserve it by
    # reusing the existing skip-teardown mechanism: the EXIT trap (installed above,
    # `trap '[ "$SKIP_TEARDOWN" = false ] && teardown' EXIT`) honours SKIP_TEARDOWN, the
    # same flag `--skip-teardown` sets. No parallel teardown path.
    SKIP_TEARDOWN=true
    log_error "Cluster PRESERVED (not torn down): it is healthy and reachable via curl; only this machine's CLI is blocked."
    log_error "After fixing access, re-run the suite to reuse it (add --skip-deploy to skip re-bootstrap)."
    log_error "To tear it down manually: re-run ./run-tests.sh without --skip-teardown (its EXIT trap tears down on normal completion); on cloud use tools/cloud-reaper.sh --cluster <name> --destroy --force."
    exit 2
fi

# --- Step 8: Gate -- run 00-smoke ---
GATE_PASSED=true
if [ ${#A_SUITES[@]} -gt 0 ] && printf '%s\n' "${A_SUITES[@]}" | grep -qx "00"; then
    log_step "Gate: running 00-smoke on Cluster A"
    if ! run_suite "00" "a"; then
        log_error "GATE FAILED: 00-smoke did not pass -- aborting all suites"
        GATE_PASSED=false
    fi
    # Remove 00 from parallel list (already ran)
    A_REMAINING=()
    for s in "${A_SUITES[@]}"; do
        [ "$s" != "00" ] && A_REMAINING+=("$s")
    done
    A_SUITES=("${A_REMAINING[@]+${A_REMAINING[@]}}")
fi

if [ "$GATE_PASSED" = true ]; then
    # --- Step 9: Run Cluster A suites (parallel) ---
    if [ ${#A_SUITES[@]} -gt 0 ]; then
        log_step "Running Cluster A suites (parallel, max ${MAX_PARALLEL:-4})"
        run_cluster_a_suites "${A_SUITES[@]}" || true
    fi

    # --- Step 9.5 (cloud only): serialize clusters — reap A, then bring up B ---
    # Runs regardless of cluster A's suite pass/fail (a failed A run still holds
    # its VMs). Reap failure is non-fatal: leftover A VMs only shrink scale-up
    # headroom, and the final teardown sweeps them again. Skipped entirely when
    # no B suites are selected — then there is no capacity pressure and the
    # normal teardown (or --skip-teardown preservation) governs cluster A.
    if [ "$ENV_TYPE" = "cloud" ] && [ ${#B_SUITES[@]} -gt 0 ]; then
        if [ "$A_SUITES_SELECTED" -gt 0 ]; then
            log_step "Reaping Cluster A before Cluster B bring-up (serialized cloud clusters)"
            reap_cloud_cluster "$CLUSTER_A_NAME" || \
                log_warn "Cluster A reap did not fully converge — proceeding to Cluster B bring-up (leftover A VMs reduce scale-up headroom; final teardown sweeps them)"
        fi
        cloud_bringup_cluster_b
    fi

    # --- Step 10: Run Cluster B suites (sequential with self-heal) ---
    if [ ${#B_SUITES[@]} -gt 0 ]; then
        log_step "Running Cluster B suites (sequential)"
        run_cluster_b_suites "${B_SUITES[@]}"
    fi
fi

# --- Step 11: Results ---
TOTAL_DURATION=$(( $(date +%s) - START_TIME ))
log_info "Total duration: ${TOTAL_DURATION}s"
set +e
print_results "$RESULTS_FILE"
FINAL_RESULT=$?
set -e

# Cleanup temp files (teardown runs from EXIT trap installed earlier)
rm -f "$RESULTS_FILE" "$TIMINGS_FILE"

exit "$FINAL_RESULT"

exit $FINAL_RESULT
