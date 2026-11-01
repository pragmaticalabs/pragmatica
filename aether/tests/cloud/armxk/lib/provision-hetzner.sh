#!/bin/bash
# provision-hetzner.sh up|down|check <run-dir> — the ONLY arm X/K file that calls the provider (through lib/cloud-driver.sh).
#
#   up     estimate + limits precheck (refuses on any problem), baseline server count, private network, ssh key, 5 core servers and
#          the worker hosts, host bootstrap (JDK 25 from the Ubuntu archive), inventory.json for armxk.py
#   down   deletes every server, network and key carrying the run label, then runs `check`
#   check  passes only when the project's server count equals the baseline read before `up` (any label, so an unlabelled
#          replacement still fails it)
#
# Topology (env, defaults per q6 §7.4 / q8 P6): ARMXK_CORE_TYPE=ccx13, ARMXK_WORKER_TYPE=cpx62, ARMXK_WORKER_HOSTS=15, ARMXK_WORKERS=100,
# ARMXK_LOCATION=nbg1 (the product pages list CPX62/CCX in NBG1 and HEL1 only), ARMXK_HOURS=3, and the project limits
# ARMXK_LIMIT_INSTANCES / _SHARED_VCPU / _DEDICATED_VCPU (defaults are the last recorded values; the CTO reads the console).
set -euo pipefail
OP=${1:?up|down|check}; RUN_DIR=${2:?run-dir}
HERE="$(cd "$(dirname "$0")" && pwd)"
CLOUD_DIR="$(cd "$HERE/../.." && pwd)"
# shellcheck source=/dev/null
source "$CLOUD_DIR/lib/cloud-driver.sh"
RUN_ID=$(basename "$RUN_DIR" | tr -c 'a-zA-Z0-9-\n' '-' | tr 'A-Z' 'a-z' | cut -c1-40)
LABEL="aether-cluster=armxk-${RUN_ID}"
NET="armxk-${RUN_ID}-net"; KEY_NAME="armxk-${RUN_ID}-key"
CORE_TYPE=${ARMXK_CORE_TYPE:-ccx13}; WORKER_TYPE=${ARMXK_WORKER_TYPE:-cpx62}
WORKER_HOSTS=${ARMXK_WORKER_HOSTS:-15}; WORKERS=${ARMXK_WORKERS:-100}
LOCATION=${ARMXK_LOCATION:-nbg1}; HOURS=${ARMXK_HOURS:-3}
log() { echo "[$(date -u +%H:%M:%SZ)] provision: $*"; }

check() {
    local baseline now
    baseline=$(cat "$RUN_DIR/server-baseline")
    now=$(driver_server_count_all)
    echo "{\"utc\":\"$(date -u +%FT%TZ)\",\"baseline\":$baseline,\"now\":$now,\"label\":\"$LABEL\",\"labelled\":$(driver_server_count_by_label "$LABEL")}" >> "$RUN_DIR/evidence/teardown-check.jsonl"
    if [ "$now" != "$baseline" ]; then
        log "TEARDOWN NOT COMPLETE: $now servers in the project, baseline $baseline"; driver_show_servers_by_label "$LABEL" || true; return 1
    fi
    log "teardown verified: server count $now == baseline $baseline"
}

case "$OP" in
    up)
        driver_require_cli || { log "hcloud CLI not installed"; exit 2; }
        mkdir -p "$RUN_DIR/evidence" "$RUN_DIR/ssh"
        python3 "$HERE/estimate.py" --core-type "$CORE_TYPE" --worker-type "$WORKER_TYPE" --worker-hosts "$WORKER_HOSTS" --hours "$HOURS" \
            --limit-instances "${ARMXK_LIMIT_INSTANCES:-20}" --limit-shared-vcpu "${ARMXK_LIMIT_SHARED_VCPU:-120}" \
            --limit-dedicated-vcpu "${ARMXK_LIMIT_DEDICATED_VCPU:-12}" --json > "$RUN_DIR/estimate.json" \
            || { log "estimate refused the topology:"; python3 -c "import json;[print(' ',p) for p in json.load(open('$RUN_DIR/estimate.json'))['problems']]"; exit 3; }
        driver_server_count_all > "$RUN_DIR/server-baseline"
        log "baseline: $(cat "$RUN_DIR/server-baseline") servers in the project before provisioning"
        ssh-keygen -q -t ed25519 -N "" -C "$KEY_NAME" -f "$RUN_DIR/ssh/armxk-key"
        driver_create_sshkey "$KEY_NAME" "$RUN_DIR/ssh/armxk-key.pub"
        driver_init_network "$NET" "10.0.42.0/24" "$LABEL"
        names=()
        for i in 1 2 3 4 5; do names+=("armxk-${RUN_ID}-core-$i"); done
        for i in $(seq 1 "$WORKER_HOSTS"); do names+=("armxk-${RUN_ID}-wh-$i"); done
        for n in "${names[@]}"; do
            case "$n" in *-core-*) t=$CORE_TYPE; role=core ;; *) t=$WORKER_TYPE; role=worker ;; esac
            driver_create_support_vm "$n" "$t" ubuntu-24.04 "$LOCATION" "$KEY_NAME" "$NET" "$LABEL" "aether-role=$role"
            log "created $n ($t)"
        done
        date +%s000 > "$RUN_DIR/provisioned-at-ms"
        : > "$RUN_DIR/hosts-ip.tsv"
        for n in "${names[@]}"; do
            printf '%s\t%s\t%s\n' "$n" "$(driver_node_public_ip "$n")" "$(driver_server_private_ip "$n")" >> "$RUN_DIR/hosts-ip.tsv"
        done
        while IFS=$'\t' read -r n pub priv; do
            for _ in $(seq 60); do ssh -i "$RUN_DIR/ssh/armxk-key" -o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new \
                -o UserKnownHostsFile="$RUN_DIR/known_hosts" "root@$pub" true 2>/dev/null && break; sleep 5; done
            ssh -i "$RUN_DIR/ssh/armxk-key" -o BatchMode=yes -o UserKnownHostsFile="$RUN_DIR/known_hosts" "root@$pub" \
                "export DEBIAN_FRONTEND=noninteractive; apt-get update -qq && apt-get install -y -qq openjdk-25-jdk-headless curl python3 >/dev/null && java -version 2>&1 | head -1 && mkdir -p /opt/armxk"
            log "bootstrapped $n ($pub / $priv)"
        done < "$RUN_DIR/hosts-ip.tsv"
        python3 - "$RUN_DIR" "$WORKERS" <<'PY'
import json, sys
run_dir, workers = sys.argv[1], int(sys.argv[2])
hosts = []
for line in open(f"{run_dir}/hosts-ip.tsv"):
    name, pub, priv = line.rstrip("\n").split("\t")
    role = "core" if "-core-" in name else "worker"
    short = ("core-host-" if role == "core" else "wh-") + name.rsplit("-", 1)[1]
    hosts.append({"name": short, "server": name, "ssh": f"root@{pub}", "addr": priv, "role": role})
estimate = json.load(open(f"{run_dir}/estimate.json"))
inv = {"mode": "hetzner", "sshKey": f"{run_dir}/ssh/armxk-key", "remoteRoot": "/opt/armxk", "javaHome": "/usr/lib/jvm/java-25-openjdk-amd64",
       "driverHost": "wh-1", "hosts": hosts, "provisionedAtMs": int(open(f"{run_dir}/provisioned-at-ms").read()), "estimate": estimate,
       "config": {"portBase": 26000, "workers": workers, "coreHeap": "4g", "workerHeap": "1g", "reps": 3, "classes": ["s0", "s6", "s3", "s4"],
                  "timing": {"idleWindowMs": 45000, "minEventMs": 30000, "quietMs": 20000, "maxEventMs": 240000, "s0WindowMs": 120000,
                             "controlMinMs": 10000, "controlQuietMs": 5000, "restoreSettleMs": 40000},
                  "armK": {"concurrency": [1, 4, 16, 64, 256], "passes": 3, "warmupS": 15, "measureS": 60, "controlS": 20}}}
json.dump(inv, open(f"{run_dir}/inventory.json", "w"), indent=1)
PY
        log "inventory written: $RUN_DIR/inventory.json" ;;
    down)
        for id in $(driver_server_ids_by_label "$LABEL"); do driver_delete_server "$id" && log "deleted server $id" || log "FAILED to delete server $id"; done
        for id in $(driver_network_ids_by_label "$LABEL"); do driver_delete_network "$id" && log "deleted network $id" || log "FAILED to delete network $id"; done
        driver_delete_sshkey "$KEY_NAME" 2>/dev/null && log "deleted ssh key" || log "ssh key not found"
        for _ in $(seq 24); do check && exit 0; sleep 5; done
        exit 1 ;;
    check) check ;;
    *) echo "unknown op $OP" >&2; exit 2 ;;
esac
