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

RESULTS_FILE="$(mktemp /tmp/aether-test-results.XXXXXX)"
RESULTS_JSON="${SCRIPT_DIR}/test-results.json"

# Cluster A: non-destructive (parallel)
COMPOSE_A="${SCRIPT_DIR}/docker-compose-a.yml"
CLUSTER_A_NAME="test-a"
CLUSTER_A_MGMT="http://${TARGET_HOST:-localhost}:5150"
CLUSTER_A_LB_APP=""
CLUSTER_A_LB_MGMT=""

# Cluster B: destructive (sequential)
COMPOSE_B="${SCRIPT_DIR}/docker-compose-b.yml"
CLUSTER_B_NAME="test-b"
CLUSTER_B_MGMT="http://${TARGET_HOST:-localhost}:5160"
CLUSTER_B_LB_APP=""
CLUSTER_B_LB_MGMT=""

# Suite assignments (from spec Section 5)
CLUSTER_A_SUITES=(00 04 06 07 08 09 10 11 14 15)
CLUSTER_B_SUITES=(02 03 05 12 13)

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
        --env)           ENV_TYPE="$2"; shift 2 ;;
        --env=*)         ENV_TYPE="${1#*=}"; shift ;;
        --suites)        SELECTED_SUITES="$2"; shift 2 ;;
        --suites=*)      SELECTED_SUITES="${1#*=}"; shift ;;
        --skip-build)    SKIP_BUILD=true; shift ;;
        --skip-deploy)   SKIP_DEPLOY=true; shift ;;
        --skip-teardown) SKIP_TEARDOWN=true; shift ;;
        -h|--help)
            echo "Usage: $0 --env docker|remote|cloud [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --env TYPE         Environment: docker, remote, or cloud (required)"
            echo "  --suites X,Y       Comma-separated suite prefixes (default: all)"
            echo "  --skip-build       Skip build.sh and blueprint builds"
            echo "  --skip-deploy      Skip cluster provisioning (reuse running clusters)"
            echo "  --skip-teardown    Leave clusters running after tests"
            echo ""
            echo "Environment variables:"
            echo "  TARGET_HOST        Host for docker/remote (default: localhost)"
            echo "  AETHER_SSH_KEY     SSH key for remote env"
            echo "  HCLOUD_TOKEN       Hetzner token for cloud env"
            echo "  AETHER_API_KEY     API key (default: aether-integration-test-key)"
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
        ;;
    cloud)
        : "${HCLOUD_TOKEN:?HCLOUD_TOKEN must be set for cloud env}"
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
            if [ "$s" = "$sel" ]; then
                filtered+=("$s")
            fi
        done
    done
    echo "${filtered[@]}"
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

    for bp in "${unique_bps[@]}"; do
        local coords="org.pragmatica.aether.test:${bp}:1.0.0"
        log_info "Pushing blueprint: ${coords} to ${cluster_endpoint}"
        aether -c "${cluster_endpoint#http://}" --api-key "${API_KEY}" artifact push "$coords" 2>/dev/null || true
        aether -c "${cluster_endpoint#http://}" --api-key "${API_KEY}" blueprint deploy "$coords" 2>/dev/null || true
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
    local cluster="$2"  # "a" or "b"

    # Resolve suite directory
    local suite_dir
    suite_dir=$(resolve_suite_dir "$suite_prefix")
    if [ -z "$suite_dir" ] || [ ! -d "$suite_dir" ]; then
        log_error "Suite not found: ${suite_prefix}"
        return 1
    fi

    local suite_name
    suite_name=$(basename "$suite_dir")

    # Check requirements
    if ! check_requirements "$suite_dir"; then
        log_info "SKIP: ${suite_name} (missing capabilities)"
        echo "{\"suite\":\"${suite_name}\",\"status\":\"skipped\",\"pass\":0,\"fail\":0,\"duration\":0}" >> "$RESULTS_FILE"
        return 0
    fi

    # Set cluster-specific endpoints
    local cluster_endpoint lb_app lb_mgmt
    if [ "$cluster" = "a" ]; then
        cluster_endpoint="$CLUSTER_A_MGMT"
        lb_app="${CLUSTER_A_LB_APP:-$CLUSTER_A_MGMT}"
        lb_mgmt="${CLUSTER_A_LB_MGMT:-$CLUSTER_A_MGMT}"
    else
        cluster_endpoint="$CLUSTER_B_MGMT"
        lb_app="${CLUSTER_B_LB_APP:-$CLUSTER_B_MGMT}"
        lb_mgmt="${CLUSTER_B_LB_MGMT:-$CLUSTER_B_MGMT}"
    fi

    # Export for the suite scripts
    export CLUSTER_ENDPOINT="$lb_mgmt"
    export APP_ENDPOINT="$lb_app"
    export DIRECT_ENDPOINT="$cluster_endpoint"
    export MGMT_PORT="${cluster_endpoint##*:}"

    # Run suite
    local start_time
    start_time=$(date +%s)
    log_info "============================================"
    log_info "  SUITE: ${suite_name} (cluster ${cluster})"
    log_info "============================================"

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
    local max_parallel=4
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
                pids=("${new_pids[@]}")
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

        # Self-heal between destructive suites
        if ! self_heal "$ENV_TYPE" "$COMPOSE_B" 5 "$CLUSTER_B_MGMT"; then
            aborted=true
        fi
    done
}

# ---------------------------------------------------------------------------
# Deploy clusters (docker env)
# ---------------------------------------------------------------------------
deploy_docker() {
    local host="${TARGET_HOST:-localhost}"

    log_step "Deploying Cluster A (non-destructive)"
    if [ "$host" = "localhost" ]; then
        docker compose -f "$COMPOSE_A" up -d 2>&1 | tail -5
    else
        scp -i "$AETHER_SSH_KEY" -o StrictHostKeyChecking=no "$COMPOSE_A" "${AETHER_SSH_USER:-root}@${host}:~/docker-compose-a.yml"
        remote_exec "cd ~ && docker compose -f docker-compose-a.yml up -d 2>&1 | tail -5"
    fi

    log_step "Deploying Cluster B (destructive)"
    if [ "$host" = "localhost" ]; then
        docker compose -f "$COMPOSE_B" up -d 2>&1 | tail -5
    else
        scp -i "$AETHER_SSH_KEY" -o StrictHostKeyChecking=no "$COMPOSE_B" "${AETHER_SSH_USER:-root}@${host}:~/docker-compose-b.yml"
        remote_exec "cd ~ && docker compose -f docker-compose-b.yml up -d 2>&1 | tail -5"
    fi
}

# ---------------------------------------------------------------------------
# Teardown clusters
# ---------------------------------------------------------------------------
teardown() {
    log_step "Tearing down clusters"
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
            aether cluster destroy --cluster "$CLUSTER_A_NAME" --yes 2>/dev/null || true
            aether cluster destroy --cluster "$CLUSTER_B_NAME" --yes 2>/dev/null || true
            ;;
    esac
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
        suite=$(echo "$line" | python3 -c "import sys,json; print(json.load(sys.stdin)['suite'])" 2>/dev/null)
        status=$(echo "$line" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])" 2>/dev/null)
        pass=$(echo "$line" | python3 -c "import sys,json; print(json.load(sys.stdin)['pass'])" 2>/dev/null)
        fail=$(echo "$line" | python3 -c "import sys,json; print(json.load(sys.stdin)['fail'])" 2>/dev/null)
        dur=$(echo "$line" | python3 -c "import sys,json; print(json.load(sys.stdin)['duration'])" 2>/dev/null)

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

# --- Step 2: Deploy clusters ---
if [ "$SKIP_DEPLOY" = false ]; then
    log_step "Deploying clusters"
    case "$ENV_TYPE" in
        docker|remote) deploy_docker ;;
        cloud)
            log_step "Bootstrapping cloud clusters"
            aether cluster bootstrap "${SCRIPT_DIR}/env/cloud-hetzner.toml" --cluster "$CLUSTER_A_NAME" --yes --wait --timeout 300
            aether cluster bootstrap "${SCRIPT_DIR}/env/cloud-hetzner-b.toml" --cluster "$CLUSTER_B_NAME" --yes --wait --timeout 300
            ;;
    esac
fi

# --- Step 3: Wait for clusters ---
log_step "Waiting for Cluster A"
wait_for_node_count_on "$CLUSTER_A_MGMT" 5 180
wait_for_leader_on "$CLUSTER_A_MGMT" 60

log_step "Waiting for Cluster B"
wait_for_node_count_on "$CLUSTER_B_MGMT" 5 180
wait_for_leader_on "$CLUSTER_B_MGMT" 60

# --- Step 4: Discover LB endpoints ---
log_step "Discovering LB endpoints"
discover_endpoints "$CLUSTER_A_MGMT"
CLUSTER_A_LB_APP="${LB_APP_ENDPOINT}"
CLUSTER_A_LB_MGMT="${LB_MGMT_ENDPOINT}"
log_info "Cluster A: app=${CLUSTER_A_LB_APP} mgmt=${CLUSTER_A_LB_MGMT}"

discover_endpoints "$CLUSTER_B_MGMT"
CLUSTER_B_LB_APP="${LB_APP_ENDPOINT}"
CLUSTER_B_LB_MGMT="${LB_MGMT_ENDPOINT}"
log_info "Cluster B: app=${CLUSTER_B_LB_APP} mgmt=${CLUSTER_B_LB_MGMT}"

# --- Step 5: Detect capabilities ---
detect_capabilities "$ENV_TYPE"

# --- Step 6: Filter suites ---
A_SUITES=($(filter_suites "${CLUSTER_A_SUITES[@]}"))
B_SUITES=($(filter_suites "${CLUSTER_B_SUITES[@]}"))

# --- Step 7: Deploy blueprints ---
if [ "$SKIP_DEPLOY" = false ]; then
    log_step "Deploying blueprints to Cluster A"
    A_BLUEPRINTS=($(collect_blueprints "${A_SUITES[@]}"))
    deploy_blueprints "$CLUSTER_A_LB_MGMT" "${A_BLUEPRINTS[@]}"

    log_step "Deploying blueprints to Cluster B"
    B_BLUEPRINTS=($(collect_blueprints "${B_SUITES[@]}"))
    deploy_blueprints "$CLUSTER_B_LB_MGMT" "${B_BLUEPRINTS[@]}"
fi

# --- Step 8: Gate -- run 00-smoke ---
GATE_PASSED=true
if printf '%s\n' "${A_SUITES[@]}" | grep -qx "00"; then
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
    A_SUITES=("${A_REMAINING[@]}")
fi

if [ "$GATE_PASSED" = true ]; then
    # --- Step 9: Run Cluster A suites (parallel) ---
    if [ ${#A_SUITES[@]} -gt 0 ]; then
        log_step "Running Cluster A suites (parallel, max 4)"
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
print_results "$RESULTS_FILE"
FINAL_RESULT=$?

# --- Step 12: Teardown ---
if [ "$SKIP_TEARDOWN" = false ]; then
    teardown
fi

# Cleanup temp file
rm -f "$RESULTS_FILE"

exit $FINAL_RESULT
