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
# 5150 is owned by aether-a-mgmt-gateway (nginx sidecar) which round-robins /api
# requests across all 5 cores and skips dead upstreams via proxy_next_upstream.
# MGMT_ENTRY_POINT therefore survives any single-core failure; tests can target
# this endpoint without pinning to a specific node's lifecycle.
CLUSTER_A_MGMT="http://${TARGET_HOST:-localhost}:5150"
# Direct (LB-less) app-HTTP fallback — node-1's host-mapped app port (see docker-compose-a.yml)
CLUSTER_A_APP_DIRECT="http://${TARGET_HOST:-localhost}:8070"
CLUSTER_A_LB_APP=""
CLUSTER_A_LB_MGMT=""

# Cluster B: destructive (sequential)
COMPOSE_B="${SCRIPT_DIR}/docker-compose-b.yml"
CLUSTER_B_NAME="test-b"
# 5160 is owned by aether-b-mgmt-gateway (nginx sidecar). The gateway is what
# decouples MGMT_ENTRY_POINT from any single core's lifecycle on cluster B's
# `restart: "no"` policy -- destructive tests can now kill ANY core, including
# node-1 or the current leader, without stranding the harness on a dead port.
CLUSTER_B_MGMT="http://${TARGET_HOST:-localhost}:5160"
# Direct (LB-less) app-HTTP fallback — node-1's host-mapped app port (see docker-compose-b.yml)
CLUSTER_B_APP_DIRECT="http://${TARGET_HOST:-localhost}:8080"
CLUSTER_B_LB_APP=""
CLUSTER_B_LB_MGMT=""

# Suite assignments (from spec Section 5)
CLUSTER_A_SUITES=(00 04 06 07 08 09 10 11 14 15)
CLUSTER_B_SUITES=(02 03 05 12 13)

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
        # Cloud has higher inter-node latency than docker-localhost: 04-streaming ran 2.7×
        # slower in the run-5 baseline, 09-artifacts ~9× slower. Scale every wait_for_*
        # / await_generation_quiesced timeout proportionally. Override via env if needed.
        export TIMEOUT_SCALE="${TIMEOUT_SCALE:-3}"
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
        aether -c "${cluster_endpoint#http://}" --api-key "${API_KEY}" artifact push "$coords" 2>/dev/null || true
        aether -c "${cluster_endpoint#http://}" --api-key "${API_KEY}" blueprint deploy "$coords" 2>&1 || \
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
        # Direct per-node mgmt ports = 5151..5155 (node-1..node-5); 5150 is the
        # mgmt sidecar gateway. MGMT_PORT+i convention now resolves node-{i+1}.
        node_base="5151"
    else
        cluster_endpoint="$CLUSTER_B_MGMT"
        lb_app="${CLUSTER_B_LB_APP:-$CLUSTER_B_APP_DIRECT}"
        lb_mgmt="${CLUSTER_B_LB_MGMT:-$CLUSTER_B_MGMT}"
        cluster_id="b"
        # Direct per-node mgmt ports = 5161..5165 (node-1..node-5); 5160 is the
        # mgmt sidecar gateway. Killing ANY core is now safe -- gateway routes
        # via proxy_next_upstream to a surviving upstream.
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
            log_warn "SKIP: ${name} (cluster B unrecoverable)"
            echo "{\"suite\":\"${name}\",\"status\":\"skipped\",\"pass\":0,\"fail\":0,\"duration\":0}" >> "$RESULTS_FILE"
            continue
        fi

        run_suite "$suite" "b" || true

        # Between destructive suites: best-effort quiesce check. We do NOT abort on
        # failure — chaos tests can leave residual CTM-provisioned replacements whose
        # snapshots haven't propagated, and skipping subsequent destructive suites just
        # turns one failure into five. Each suite is responsible for its own preconditions
        # via the wait_for_cluster / wait_for_leader helpers in run_test().
        local quiesce_start
        quiesce_start=$(date +%s)
        # 60s base × TIMEOUT_SCALE: 60s docker / 180s cloud. Best-effort barrier;
        # continue-on-fail because each suite's own `wait_for_cluster` re-establishes
        # preconditions if churn lingers.
        await_generation_quiesced "$CLUSTER_B_MGMT" "current" 60 || \
            log_warn "Cluster B did not quiesce within 60s after suite ${suite} — continuing"
        # Phase=NORMAL barrier: addresses the documented 02-chaos→03-scaling
        # regression (investigation 2026-05-11) where elevated quorum latency from
        # cluster B's chaos-tail makes the next suite's consensus puts (e.g.
        # /api/cluster/scale) hit their curl timeout. Wait up to 180s × TIMEOUT_SCALE
        # for phase=NORMAL so the next suite enters a settled cluster. Soft on
        # failure — the chaos-test recovery may genuinely need >540s on docker-remote
        # and the next suite's own preconditions will surface that case.
        export CLUSTER_ENDPOINT="$CLUSTER_B_MGMT"
        wait_for_phase "NORMAL" 180 || \
            log_warn "Cluster B did not reach phase=NORMAL within budget after suite ${suite} — next suite may inherit BOOTING state"
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

deploy_docker() {
    local host="${TARGET_HOST:-localhost}"

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
        docker rm -f $(docker ps -aq --filter "name=aether-core-node-") 2>/dev/null || true
        docker volume rm -f aether_pgdata 2>/dev/null || true
        docker compose -f "$COMPOSE_A" up -d 2>&1 | tail -5
    else
        remote_scp "$COMPOSE_A" "~/docker-compose-a.yml"
        remote_exec "cd ~ && docker compose -f docker-compose-a.yml down -v 2>/dev/null || true; docker rm -f \$(docker ps -aq --filter name=aether-core-node-) 2>/dev/null || true; docker volume rm -f aether_pgdata 2>/dev/null || true; docker compose -f docker-compose-a.yml up -d 2>&1 | tail -5"
    fi

    log_step "Deploying Cluster B (destructive)"
    if [ "$host" = "localhost" ]; then
        docker compose -f "$COMPOSE_B" down -v 2>/dev/null || true
        docker compose -f "$COMPOSE_B" up -d 2>&1 | tail -5
    else
        remote_scp "$COMPOSE_B" "~/docker-compose-b.yml"
        remote_exec "cd ~ && docker compose -f docker-compose-b.yml down -v 2>/dev/null || true; docker compose -f docker-compose-b.yml up -d 2>&1 | tail -5"
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
                docker compose -f "$COMPOSE_A" down -v 2>/dev/null || true
                docker compose -f "$COMPOSE_B" down -v 2>/dev/null || true
            else
                remote_exec "docker compose -f ~/docker-compose-a.yml down -v 2>/dev/null || true"
                remote_exec "docker compose -f ~/docker-compose-b.yml down -v 2>/dev/null || true"
            fi
            # Clean up orphaned CTM containers
            docker rm -f $(docker ps -aq --filter "name=aether-core") 2>/dev/null || true
            ;;
        cloud)
            # `aether cluster destroy` has no --cluster flag (only operates on the active cluster).
            # Use cloud-reaper.sh which filters by `aether-cluster` label — works regardless of
            # bootstrap-state.json existence, idempotent, exits 0 if nothing to destroy.
            [ ${#A_SUITES[@]} -gt 0 ] && ("${REPO_ROOT}/../tools/cloud-reaper.sh" --cluster "$CLUSTER_A_NAME" --destroy --force 2>&1 | tail -3 || true)
            [ ${#B_SUITES[@]} -gt 0 ] && ("${REPO_ROOT}/../tools/cloud-reaper.sh" --cluster "$CLUSTER_B_NAME" --destroy --force 2>&1 | tail -3 || true)
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

    local total=0 passed=0 failed=0 skipped=0
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
        esac
    done < "$results_file"

    echo "========================================"
    printf "  Total: %d | Passed: %d | Failed: %d | Skipped: %d\n" "$total" "$passed" "$failed" "$skipped"
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

    [ "$failed" -eq 0 ]
}

# ===========================================================================
# MAIN
# ===========================================================================
log_info "Integration test runner -- env=${ENV_TYPE}"
START_TIME=$(date +%s)

# --- Step 1: Build ---
if [ "$SKIP_BUILD" = false ] && [ -x "${REPO_ROOT}/build.sh" ]; then
    log_step "Building project"
    "${REPO_ROOT}/build.sh"
fi

# --- Compute selected suites early so cluster bootstrap can be skipped per-cluster ---
A_SUITES=($(filter_suites "${CLUSTER_A_SUITES[@]}"))
B_SUITES=($(filter_suites "${CLUSTER_B_SUITES[@]}"))

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
            if [ ${#A_SUITES[@]} -gt 0 ]; then
                aether cluster bootstrap "$CLOUD_TOML_A" --cluster "$CLUSTER_A_NAME" --yes --wait --timeout 300
                # Cloud override: derive endpoints from the freshly-provisioned VM's public IP.
                # Default CLUSTER_A_MGMT/APP point at docker-compose host-mapped ports (5150/8070),
                # which don't exist on Hetzner VMs (mgmt=8080, app=8070 per cloud-hetzner.toml).
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
            else
                log_info "Skipping Cluster A bootstrap (no A-suites selected)"
            fi
            if [ ${#B_SUITES[@]} -gt 0 ]; then
                aether cluster bootstrap "$CLOUD_TOML_B" --cluster "$CLUSTER_B_NAME" --yes --wait --timeout 300
                # Cloud override: derive endpoints from the freshly-provisioned VM's public IP.
                cluster_b_ip=$(BOOTSTRAP_CLUSTER_NAME="$CLUSTER_B_NAME" CLOUD_SOURCE_NAME="hetzner-eu" cloud_public_ip node-1)
                if [ -n "$cluster_b_ip" ]; then
                    CLUSTER_B_MGMT="http://${cluster_b_ip}:8080"
                    CLUSTER_B_APP_DIRECT="http://${cluster_b_ip}:8070"
                    log_info "Cluster B endpoints: mgmt=${CLUSTER_B_MGMT} app=${CLUSTER_B_APP_DIRECT}"
                else
                    log_warn "Could not resolve Cluster B public IP; falling back to default ${CLUSTER_B_MGMT}"
                fi
                log_info "Cluster B: tests use static api key; CLI uses ${HOME}/.aether/clusters/${CLUSTER_B_NAME}/api-key"
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

if [ ${#B_SUITES[@]} -gt 0 ]; then
    log_step "Waiting for Cluster B"
    wait_for_node_count_on "$CLUSTER_B_MGMT" 5 180
    wait_for_leader_on "$CLUSTER_B_MGMT" 60
fi

# Gate cluster readiness on ClusterGeneration quiescence — avoids racing the
# later blueprint-deploy phase against a cluster that still hasn't converged.
[ ${#A_SUITES[@]} -gt 0 ] && (await_generation_quiesced "$CLUSTER_A_MGMT" "current" 60 || log_warn "Cluster A snapshot not quiesced yet")
[ ${#B_SUITES[@]} -gt 0 ] && (await_generation_quiesced "$CLUSTER_B_MGMT" "current" 60 || log_warn "Cluster B snapshot not quiesced yet")

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

if [ ${#B_SUITES[@]} -gt 0 ]; then
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

# --- Step 6.5: Drop ghost CTM-provisioned containers from previous runs ---
# Bash dynamic-scoping bug + Wave 3 changes have historically allowed CTM
# to auto-provision phantom replacement containers (named aether-core-node-N-XXX)
# that flap-loop in the topology, starve QUIC backpressure, and stall consensus.
# A clean run must start with the docker-compose-defined nodes ONLY.
if [ "$SKIP_DEPLOY" = false ] && [ "$ENV_TYPE" != "cloud" ]; then
    log_step "Cleaning up ghost CTM-provisioned containers"
    if [ "$ENV_TYPE" = "docker" ]; then
        docker rm -f $(docker ps -aq --filter "name=aether-core-node-") 2>/dev/null || true
    else
        remote_exec "docker rm -f \$(docker ps -aq --filter name=aether-core-node-) 2>/dev/null || true" 2>&1 | tail -1 || true
    fi
fi

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

    if [ ${#B_SUITES[@]} -gt 0 ]; then
        log_step "Deploying blueprints to Cluster B"
        B_BLUEPRINTS=($(collect_blueprints "${B_SUITES[@]}"))
        [ ${#B_BLUEPRINTS[@]} -gt 0 ] && deploy_blueprints "$CLUSTER_B_LB_MGMT" "${B_BLUEPRINTS[@]}"
        await_generation_quiesced "$CLUSTER_B_LB_MGMT" "current+1" 60 || \
            log_warn "Cluster B did not quiesce after blueprint deploy"
    fi
fi
BLUEPRINT_ELAPSED=$(( $(date +%s) - BLUEPRINT_START ))
printf 'blueprint_deploy=%s\n' "$BLUEPRINT_ELAPSED" >> "$TIMINGS_FILE"

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
