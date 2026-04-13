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
    # Reset defaults
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
