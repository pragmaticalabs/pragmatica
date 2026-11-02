#!/bin/bash
# armxk.sh — arm X (cloud confirmation of M-1) + arm K (core capacity) harness, #2512 / q6 §7.4 P6. See README.md.
#
#   armxk.sh estimate [estimate.py args]                     cost estimate from list prices; no API call
#   armxk.sh dry-run  <user@host> <checkout> [run-dir]       the whole run against a local multi-node cluster on ONE host; spends nothing
#   armxk.sh cloud-run <build-host> <checkout> [run-dir]     SPENDS MONEY. Run by the CTO or the owner only, as:
#                                                              with-hcloud env ARMXK_SPEND=yes bash armxk.sh cloud-run ...
#   armxk.sh stage-local <user@host> <checkout> <run-dir>     the dry run's inventory and staging only, to run steps one by one
#   armxk.sh teardown <run-dir>                              deletes the run's servers, then checks the count is back at the baseline
#   armxk.sh teardown-check <run-dir>                        the check alone
#   armxk.sh <armxk.py command> <run-dir>                    one step: cores-up, control, armk, workers-up, s0, s3, s4, s6, pull, stop-all
#
# <checkout> is a pragmatica checkout ON the build host whose `install -DskipTests -pl aether/node,aether/e2e-tests/echo-slice -am`
# has run; the node jar, the echo slice and the M-1 instrument source all come from it.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CLOUD_DIR="$(cd "$HERE/.." && pwd)"
CMD=${1:?usage: see the header of armxk.sh}; shift

log() { echo "[$(date -u +%H:%M:%SZ)] $*"; }
ssh_opts=(-o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new)

# ---------------------------------------------------------------------------------------------------------------------------------
# stage: build the agent on the build host, bundle, copy to every inventory host, write per-run secrets (0600, never printed).
# ---------------------------------------------------------------------------------------------------------------------------------
stage() {
    local run_dir=$1 build_host=$2 checkout=$3 key=$4
    local inv="$run_dir/inventory.json" bundle="$run_dir/bundle.tgz"
    local remote_tmp="/tmp/armxk-bundle-$$"
    log "stage: building m1probe.jar on $build_host from $checkout"
    ssh -i "$key" "${ssh_opts[@]}" "$build_host" "rm -rf $remote_tmp && mkdir -p $remote_tmp/bin $remote_tmp/lib $remote_tmp/driver $remote_tmp/agent"
    scp -q -i "$key" "${ssh_opts[@]}" -r "$HERE/agent/." "$build_host:$remote_tmp/agent/"
    scp -q -i "$key" "${ssh_opts[@]}" "$HERE/lib/node.sh" "$build_host:$remote_tmp/lib/"
    scp -q -i "$key" "${ssh_opts[@]}" "$HERE/driver/ArmKDriver.java" "$build_host:$remote_tmp/driver/"
    ssh -i "$key" "${ssh_opts[@]}" "$build_host" "set -e; export PATH=/usr/lib/jvm/java-25-openjdk-amd64/bin:\$PATH; cd $remote_tmp
        bash agent/build-agent.sh $checkout $checkout/aether/node/target/aether-node.jar bin/m1probe.jar
        cp $checkout/aether/node/target/aether-node.jar bin/
        V=\$(sed -n 's:.*<version>\(1[^<]*\)</version>.*:\1:p' $checkout/aether/e2e-tests/echo-slice/pom.xml | head -1)
        cp $checkout/aether/e2e-tests/echo-slice/target/echo-slice-echo-service-\$V.jar bin/echo-slice.jar
        echo \$V > bin/echo-version
        git -C $checkout rev-parse HEAD > bin/checkout-sha
        sha256sum bin/* > bin/SHA256SUMS
        tar czf bundle.tgz bin lib driver"
    scp -q -i "$key" "${ssh_opts[@]}" "$build_host:$remote_tmp/bundle.tgz" "$bundle"
    scp -q -i "$key" "${ssh_opts[@]}" "$build_host:$remote_tmp/bin/SHA256SUMS" "$run_dir/SHA256SUMS"
    ssh -i "$key" "${ssh_opts[@]}" "$build_host" "cat $remote_tmp/bin/echo-version $remote_tmp/bin/checkout-sha; rm -rf $remote_tmp" > "$run_dir/build-facts"
    python3 - "$inv" "$run_dir/build-facts" "$run_dir/SHA256SUMS" <<'PY'
import json, sys
inv = json.load(open(sys.argv[1])); version, sha = open(sys.argv[2]).read().split()[:2]
sums = dict(reversed(l.split()) for l in open(sys.argv[3]) if l.strip())
inv["echoSlice"] = {"group": "org.pragmatica-lite.aether.test", "artifact": "echo-slice-echo-service", "version": version}
inv["echoSha256"] = sums.get("bin/echo-slice.jar"); inv["checkoutSha"] = sha; inv["nodeJarSha256"] = sums.get("bin/aether-node.jar")
json.dump(inv, open(sys.argv[1], "w"), indent=1)
PY
    # secrets: generated once per run on this machine, copied to each host 0600; never echoed
    if [ ! -f "$run_dir/secrets/cluster-secret" ]; then
        mkdir -p "$run_dir/secrets"; chmod 700 "$run_dir/secrets"
        ( umask 077; head -c 32 /dev/urandom | base64 | tr -d '\n' > "$run_dir/secrets/cluster-secret"
          printf '%s:armxk:service:ADMIN' "$(head -c 24 /dev/urandom | base64 | tr -d '/+=\n')" > "$run_dir/secrets/api-keys" )
    fi
    local root; root=$(python3 -c "import json;print(json.load(open('$inv'))['remoteRoot'])")
    for target in $(python3 -c "import json;print(' '.join(sorted({h['ssh'] for h in json.load(open('$inv'))['hosts']})))"); do
        log "stage: -> $target:$root"
        ssh -i "$key" "${ssh_opts[@]}" "$target" "mkdir -p $root/secrets && chmod 700 $root/secrets"
        scp -q -i "$key" "${ssh_opts[@]}" "$bundle" "$target:$root/bundle.tgz"
        scp -q -i "$key" "${ssh_opts[@]}" "$run_dir/secrets/cluster-secret" "$run_dir/secrets/api-keys" "$target:$root/secrets/"
        ssh -i "$key" "${ssh_opts[@]}" "$target" "cd $root && tar xzf bundle.tgz && chmod 600 secrets/* && sha256sum -c --quiet bin/SHA256SUMS && java -version 2>&1 | head -1"
    done
}

local_inventory() {
    # Dry run: 5 core "hosts" and 2 worker hosts that are all the same machine. Addresses are 127.0.0.1; ports are unique per node
    # (the orchestrator allocates a 10-port slot per node from portBase, below the 32768 ephemeral floor).
    local run_dir=$1 target=$2 key=$3
    python3 - "$run_dir/inventory.json" "$target" "$key" "$run_dir" <<'PY'
import json, sys
path, target, key, run_dir = sys.argv[1:]
hosts = [{"name": f"core-host-{i}", "ssh": target, "addr": "127.0.0.1", "role": "core"} for i in range(1, 6)]
hosts += [{"name": f"wh-{i}", "ssh": target, "addr": "127.0.0.1", "role": "worker"} for i in (1, 2)]
inv = {"mode": "local", "sshKey": key, "remoteRoot": "/home/aether/work/cloudx-armxk-dry", "driverHost": "wh-1", "hosts": hosts,
       "config": {"portBase": 26000, "workers": 6, "coreHeap": "512m", "workerHeap": "384m", "reps": 2,
                  "classes": ["s0", "s6", "s3", "s4"],
                  "timing": {"idleWindowMs": 15000, "minEventMs": 10000, "quietMs": 10000, "maxEventMs": 120000, "s0WindowMs": 20000,
                             "controlMinMs": 5000, "controlQuietMs": 5000, "restoreSettleMs": 15000, "admitTimeoutMs": 180000},
                  "armK": {"concurrency": [1, 4, 16], "passes": 3, "warmupS": 5, "measureS": 15, "controlS": 8}}}
json.dump(inv, open(path, "w"), indent=1)
PY
}

case "$CMD" in
    estimate) exec python3 "$HERE/lib/estimate.py" "$@" ;;
    dry-run|stage-local)
        TARGET=${1:?user@host}; CHECKOUT=${2:?checkout on the host}
        RUN_DIR=${3:-"$HERE/runs/dry-$(date -u +%Y%m%dT%H%M%SZ)"}
        KEY=${ARMXK_SSH_KEY:-$HOME/.ssh/aether_test}
        mkdir -p "$RUN_DIR"
        local_inventory "$RUN_DIR" "$TARGET" "$KEY"
        ROOT=$(python3 -c "import json;print(json.load(open('$RUN_DIR/inventory.json'))['remoteRoot'])")
        ssh -i "$KEY" "${ssh_opts[@]}" "$TARGET" "if [ -d $ROOT/nodes ]; then for d in $ROOT/nodes/*/; do bash $ROOT/lib/node.sh stop $ROOT \$(basename \$d) || true; done; fi; rm -rf $ROOT && mkdir -p $ROOT"
        stage "$RUN_DIR" "$TARGET" "$CHECKOUT" "$KEY"
        [ "$CMD" = stage-local ] && exit 0
        rc=0
        python3 "$HERE/lib/armxk.py" run-all --run-dir "$RUN_DIR" || rc=$?
        python3 "$HERE/lib/armxk.py" pull --run-dir "$RUN_DIR" || true
        python3 "$HERE/lib/armxk.py" stop-all --run-dir "$RUN_DIR" || true
        log "dry run finished rc=$rc; evidence in $RUN_DIR/evidence"
        exit $rc ;;
    cloud-run)
        BUILD_HOST=${1:?build host user@host}; CHECKOUT=${2:?checkout on the build host}
        RUN_DIR=${3:-"$HERE/runs/cloud-$(date -u +%Y%m%dT%H%M%SZ)"}
        [ "${ARMXK_SPEND:-}" = yes ] || { echo "refusing: cloud-run creates billable servers; set ARMXK_SPEND=yes (CTO/owner only, via with-hcloud)" >&2; exit 2; }
        [ -n "${HCLOUD_TOKEN:-}" ] || { echo "refusing: no provider credential in this process (run through with-hcloud)" >&2; exit 2; }
        mkdir -p "$RUN_DIR"
        bash "$HERE/lib/provision-hetzner.sh" up "$RUN_DIR"
        trap 'bash "$HERE/lib/provision-hetzner.sh" down "$RUN_DIR"' EXIT
        stage "$RUN_DIR" "$BUILD_HOST" "$CHECKOUT" "$RUN_DIR/ssh/armxk-key"
        python3 "$HERE/lib/armxk.py" run-all --run-dir "$RUN_DIR"
        python3 "$HERE/lib/armxk.py" pull --run-dir "$RUN_DIR" ;;
    teardown) exec bash "$HERE/lib/provision-hetzner.sh" down "${1:?run-dir}" ;;
    teardown-check) exec bash "$HERE/lib/provision-hetzner.sh" check "${1:?run-dir}" ;;
    cores-up|workers-up|control|armk|s0|s3|s4|s6|pull|stop-all|leader)
        exec python3 "$HERE/lib/armxk.py" "$CMD" --run-dir "${1:?run-dir}" ;;
    *) echo "unknown command $CMD" >&2; exit 2 ;;
esac
