#!/bin/bash
# suite.sh -- Suite metadata parsing and capability checking

# Parse a suite.conf file, exporting its vars
parse_suite_conf() {
    local suite_dir="$1"
    local conf_file="${suite_dir}/suite.conf"
    if [ ! -f "$conf_file" ]; then
        echo "WARN: No suite.conf in ${suite_dir}" >&2
        return 1
    fi
    # Reset defaults. CRITICAL caller-side note: any function that calls this and
    # has a local of the same name (`cluster`, `destructive`, `requires`, etc.)
    # will have its local CLOBBERED by `source $conf_file` due to Bash dynamic
    # scoping. This is why `run_suite` must name its argument `target_cluster`
    # (not `cluster`) — see run-tests.sh:204. `declare -g` is bash 4+ only and
    # silently fails on bash 3.2 (macOS), so we cannot enforce globalness here.
    tags=""
    cluster="non-destructive"
    destructive="false"
    requires=""
    blueprint="test-echo"
    estimated_duration="1m"
    description=""
    # Source the conf file
    source "$conf_file"
}

# Check if all required capabilities are met
check_requirements() {
    local suite_dir="$1"
    parse_suite_conf "$suite_dir" || return 0  # No conf = no requirements

    if [ -z "$requires" ]; then
        return 0
    fi

    IFS=',' read -ra reqs <<< "$requires"
    for req in "${reqs[@]}"; do
        [ -z "$req" ] && continue
        local val="${!req:-false}"
        if [ "$val" != "true" ]; then
            return 1
        fi
    done
    return 0
}

# Check if a suite belongs to a specific cluster
is_cluster() {
    local suite_dir="$1"
    local target_cluster="$2"
    parse_suite_conf "$suite_dir" || return 1
    [ "$cluster" = "$target_cluster" ]
}

# Get the blueprint name for a suite
suite_blueprint() {
    local suite_dir="$1"
    parse_suite_conf "$suite_dir" || echo "test-echo"
    echo "$blueprint"
}

# ---------------------------------------------------------------------------
# Capability Detection
# ---------------------------------------------------------------------------

# Detect available capabilities based on environment type.
# Exports CAP_CHAOS, CAP_SCALING, CAP_NETWORK_PARTITION, CAP_PERSISTENCE.
detect_capabilities() {
    local env_type="$1"

    # Always available
    export CAP_CHAOS=true
    export CAP_SCALING=true
    export CAP_NETWORK_PARTITION=false
    export CAP_PERSISTENCE=false

    # Network partition: docker/remote only (iptables)
    case "$env_type" in
        docker|remote) export CAP_NETWORK_PARTITION=true ;;
    esac

    # Persistence: check if PostgreSQL is reachable
    if command -v pg_isready &>/dev/null; then
        if pg_isready -h "${PG_HOST:-${TARGET_HOST:-localhost}}" -p "${PG_PORT:-5432}" -U forge -q 2>/dev/null; then
            export CAP_PERSISTENCE=true
        fi
    else
        # Fallback: try TCP connect
        if timeout 3 bash -c "echo >/dev/tcp/${PG_HOST:-${TARGET_HOST:-localhost}}/${PG_PORT:-5432}" 2>/dev/null; then
            export CAP_PERSISTENCE=true
        fi
    fi

    log_info "Capabilities: CHAOS=${CAP_CHAOS} SCALING=${CAP_SCALING} PARTITION=${CAP_NETWORK_PARTITION} PERSISTENCE=${CAP_PERSISTENCE}"
}
