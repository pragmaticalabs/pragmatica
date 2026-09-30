#!/bin/bash
# lib/capture.sh — node-log capture for failed suites. Sourced by lib/common.sh.
#
# capture_node_logs is the suite-end capture (run-tests.sh). The fail-time hooks below exist because
# suite-end is too late: on 2026-09-30 Kill_node_during_active_load failed in 02-chaos and a LATER
# destructive step (the S20 full-drain rebootstrap in test-self-drain-quorum-loss.sh) recreated every
# container before the suite-end capture ran, so no log from the failing window survived.
#
#   _failcap_on_fail              called by log_fail: captures once per failing test, at its FIRST [FAIL].
#   capture_before_destructive S  called at the top of restart_all_nodes and before the cloud reap: captures
#                                 whenever this suite already has a [FAIL] recorded (in ANY test process).
#
# Both write to failure-logs/<suite>/<test>/<UTC-stamp>-<reason>/, beside (never inside) the suite-end
# directory, with the same content and tail/window as the suite-end capture. They are inert unless
# run-tests.sh (or a test) sets SUITE_FAILCAP_DIR, so stub suites never reach docker or ssh, and they
# run in a subshell with the [FAIL] file redirected, so they cannot change any test verdict.

_CAPTURE_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_failcap_root() { echo "${AETHER_FAILURE_LOGS_DIR:-${_CAPTURE_LIB_DIR}/../failure-logs}"; }

# Best-effort node-log capture for a failed suite. Never fails the run: it exists to
# preserve evidence, and losing evidence must not also lose the result that produced it.
capture_node_logs() {
    local suite_name="$1" target_cluster="$2"
    # Start of the window to capture (epoch seconds); the suite's start when called from
    # run_suite. Defaults to the last hour so a caller that omits it still gets a bound.
    local since_epoch="${3:-$(( $(date +%s) - 3600 ))}"
    # A caller-supplied out_dir (fail-time and pre-destructive captures) is a fresh timestamped
    # directory, so it needs no stale-clearing and never touches the suite-end directory.
    local out_dir="${4:-}"

    if [ -z "$out_dir" ]; then
        out_dir="$(_failcap_root)/${suite_name}"
        mkdir -p "$out_dir" 2>/dev/null || return 0
        # Clear STALE captures first: this dir accumulates across runs, and run3's diagnosis
        # nearly used run2's node-5.log sitting beside run3's fresh files. A capture must
        # only ever contain THIS run's evidence.
        rm -f "${out_dir}"/*.log "${out_dir}/provisioning-snapshot.txt" 2>/dev/null || true
    else
        mkdir -p "$out_dir" 2>/dev/null || return 0
    fi
    date -u '+captured %Y-%m-%dT%H:%M:%SZ' > "${out_dir}/capture-manifest.txt" 2>/dev/null || true

    local names
    case "$ENV_TYPE" in
        docker)
            # Bounded: a hung docker daemon must not hang the test process that now calls this mid-test.
            names=$(_run_with_timeout "${CLOUD_CAPTURE_DOCKER_TIMEOUT_S:-60}" docker ps -a --format '{{.Names}}' --filter "name=aether-${target_cluster}-node-" 2>/dev/null)
            for n in $names; do
                _run_with_timeout "${CLOUD_CAPTURE_DOCKER_TIMEOUT_S:-60}" docker logs --tail 400 "$n" > "${out_dir}/${n}.log" 2>&1 || true
            done
            ;;
        remote)
            names=$(remote_exec "docker ps -a --format '{{.Names}}' --filter name=aether-${target_cluster}-node-" 2>/dev/null)
            for n in $names; do
                remote_exec "docker logs --tail 400 ${n}" > "${out_dir}/${n}.log" 2>&1 || true
            done
            # Streamed logs survive `docker rm` (auto-heal destroys a dying node's container
            # WITH its logs — node-5 in run2, node-3 in run4 died undiagnosable). The streamer
            # daemon (start_log_streamers, lib/cluster.sh) appends to per-container files on
            # the remote host; fetch whatever it has, prefixed so live-capture and streamed
            # views of the same node stay distinguishable.
            local streamed
            streamed=$(remote_exec "ls /tmp/aether-node-logs/*.log 2>/dev/null" 2>/dev/null || true)
            for f in $streamed; do
                remote_exec "tail -c 2000000 ${f}" > "${out_dir}/streamed-$(basename "$f")" 2>&1 || true
            done
            ;;
        cloud)
            # Capture from every VM of this cluster at SUITE END (after each test's own cleanup
            # restore — NOT at the instant of failure; a restore that re-bootstrapped the cluster
            # leaves only the new generation to read, and the manifest says which VMs answered).
            # Previously this branch did not exist: cloud fell through to `return 0` after writing
            # the manifest, so every failed cloud suite carried a "captured" manifest and no logs
            # (2026-09-23, 8 of 8 failed suites), and by run end those VMs had been reaped.
            # Bounded by TIME, not lines: `--since` the suite's start, because nodes log thousands
            # of lines a minute during churn and a fixed tail can end before the failing test.
            # _cloud_running_vm_ips matches seeds by IP and CTM replacements by node-id name, so
            # a replacement labelled with a different `aether-cluster` value is still captured.
            local cluster_name ips ip enum_rc=0 rc ok=0 attempted=0
            if [ -z "${AETHER_SSH_KEY:-}" ]; then
                echo "AETHER_SSH_KEY unset — cannot reach VMs, nothing captured" >> "${out_dir}/capture-manifest.txt" 2>/dev/null || true
                log_warn "${suite_name}: AETHER_SSH_KEY unset — cloud node-log capture skipped"
                return 0
            fi
            # CLUSTER_A_NAME / CLUSTER_B_NAME are plain variables of the run-tests.sh process and do not reach the
            # `bash "$test_file"` children that call this at fail time; BOOTSTRAP_CLUSTER_NAME is exported per suite.
            if [ "$target_cluster" = "a" ]; then cluster_name="${CLUSTER_A_NAME:-${BOOTSTRAP_CLUSTER_NAME:-}}"; else cluster_name="${CLUSTER_B_NAME:-${BOOTSTRAP_CLUSTER_NAME:-}}"; fi
            if [ -z "$cluster_name" ]; then
                echo "cluster name unknown (the cluster-name variable and BOOTSTRAP_CLUSTER_NAME are both unset) — nothing captured" >> "${out_dir}/capture-manifest.txt" 2>/dev/null || true
                log_warn "${suite_name}: cloud node-log capture has no cluster name — nothing captured"
                return 0
            fi
            ips=$(_cloud_running_vm_ips "$cluster_name" 2>/dev/null) || enum_rc=$?
            if [ "$enum_rc" -ne 0 ]; then
                # Unavailable is not empty (_cloud_running_vm_ips's own contract).
                echo "VM enumeration UNAVAILABLE for cluster ${cluster_name} (rc=${enum_rc}) — nothing captured" >> "${out_dir}/capture-manifest.txt" 2>/dev/null || true
                log_warn "${suite_name}: cloud VM enumeration unavailable (rc=${enum_rc}) — nothing captured"
                return 0
            fi
            local remote_cmd="docker logs --timestamps --since ${since_epoch} aether-node"
            [ "${CLOUD_RUNTIME:-container}" = "jvm" ] && remote_cmd="journalctl -u aether-node --no-pager --since @${since_epoch} -o short-iso"
            echo "window: since epoch ${since_epoch} (suite start)" >> "${out_dir}/capture-manifest.txt" 2>/dev/null || true
            for ip in $ips; do
                attempted=$((attempted + 1))
                # Outer bound covers connect + transfer; the remote `timeout` covers a command that
                # hangs on a live connection, which ssh keepalives cannot detect (#628 note at
                # common.sh remote_exec_bounded). A capture must never stall the run it serves.
                rc=0
                _run_with_timeout "${CLOUD_CAPTURE_SSH_TIMEOUT_S:-90}" \
                    ssh -n "${SSH_OPTS[@]}" -i "${AETHER_SSH_KEY}" "${CLOUD_SSH_USER:-root}@${ip}" \
                    "hostname; timeout ${CLOUD_CAPTURE_REMOTE_TIMEOUT_S:-60} ${remote_cmd}" > "${out_dir}/vm-${ip}.log" 2>&1 || rc=$?
                [ "$rc" -eq 0 ] && ok=$((ok + 1))
                printf 'vm %s rc=%s lines=%s\n' "$ip" "$rc" "$(wc -l < "${out_dir}/vm-${ip}.log" | tr -d ' ')" \
                    >> "${out_dir}/capture-manifest.txt" 2>/dev/null || true
            done
            provisioning_snapshot > "${out_dir}/provisioning-snapshot.txt" 2>&1 || true
            if [ "$attempted" -eq 0 ]; then
                # Say so: a manifest with nothing beside it must never read as a capture.
                echo "NO VMs found for cluster ${cluster_name} — nothing captured" >> "${out_dir}/capture-manifest.txt" 2>/dev/null || true
                log_warn "${suite_name}: node-log capture found NO VMs for cluster ${cluster_name} — nothing captured"
                return 0
            fi
            echo "captured ${ok} of ${attempted} VM(s)" >> "${out_dir}/capture-manifest.txt" 2>/dev/null || true
            if [ "$ok" -eq 0 ]; then
                log_warn "${suite_name}: node-log capture reached ${attempted} VM(s) and NONE returned logs (see rc= in ${out_dir}/capture-manifest.txt)"
                return 0
            fi
            ;;
        *) return 0 ;;
    esac

    log_info "${suite_name}: node logs captured to ${out_dir}"
}

# The capture writes NOTHING to stdout or stderr. log_fail is called inside callers' command substitutions,
# including `$(fn 2>&1)`, so any line the capture printed there would land in that caller's data. Its own output
# goes to one notes file per suite (never the terminal: a /dev/tty write fails without a controlling terminal, which
# kills a `set -e` caller, and can stop the process under `tostop`). run-tests.sh prints one summary line per suite.
#
# A diagnostic hook must never be able to kill a test. The two entry points (_failcap_on_fail,
# capture_before_destructive) are called as PLAIN statements from `set -euo pipefail` scripts, so each is a thin
# wrapper that runs its body as `body || true`: errexit is ignored inside a command tested by `||`, so a failing
# `ls | wc` (pipefail, no matching directory yet) or any other non-zero status stays inside the hook.
_failcap_notes_file() {
    local f="$(_failcap_root)/${SUITE_TAG:-no-suite}/failtime-capture-notes.txt"
    mkdir -p "$(dirname "$f")" 2>/dev/null || true
    echo "$f"
}
_failcap_note() {
    printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$1" >> "$(_failcap_notes_file)" 2>/dev/null || true
    return 0
}

# Cap on pre-destructive captures per suite: restore paths call restart_all_nodes from every cleanup, and a
# cloud capture costs up to CLOUD_CAPTURE_SSH_TIMEOUT_S per VM.
FAILCAP_MAX_PRE_DESTRUCTIVE="${FAILCAP_MAX_PRE_DESTRUCTIVE:-4}"
# Cap on first-fail captures per suite: a broken cluster fails every later test, and each would capture again.
FAILCAP_MAX_FIRST_FAIL="${FAILCAP_MAX_FIRST_FAIL:-6}"

# _failcap_capture <reason> — one capture into a fresh timestamped per-test directory. Output goes to the notes
# file (see above) and nothing here can fail the caller.
_failcap_capture() {
    local reason="${1:-unspecified}" suite test dir
    suite="${SUITE_TAG:-no-suite}"
    test="${TEST_TAG:-outside-a-test}"
    dir="$(_failcap_root)/${suite}/${test}/$(date -u '+%Y%m%dT%H%M%SZ')-${reason}"
    (
        HARNESS_FAIL_FILE=/dev/null   # a log_fail inside the capture must not add a [FAIL] to the test
        _FAILCAP_ACTIVE=1
        # Tighter per-node bounds than the suite-end capture: this runs inside a live test, once per failing test.
        export CLOUD_CAPTURE_SSH_TIMEOUT_S="${FAILCAP_SSH_TIMEOUT_S:-30}" CLOUD_CAPTURE_REMOTE_TIMEOUT_S="${FAILCAP_REMOTE_TIMEOUT_S:-25}" \
               CLOUD_CAPTURE_DOCKER_TIMEOUT_S="${FAILCAP_DOCKER_TIMEOUT_S:-30}"
        capture_node_logs "$suite" "${CLUSTER_ID:-a}" "${SUITE_START_EPOCH:-$(( $(date +%s) - 3600 ))}" "$dir"
    ) >> "$(_failcap_notes_file)" 2>&1 || true
    # A capture directory with no node logs (manifest only) must read as a failure to capture, not as a capture.
    if [ -z "$(find "$dir" -name '*.log' -size +0 2>/dev/null | head -1)" ]; then
        _failcap_note "[WARN]  fail-time capture (${reason}) produced NO node logs in ${dir} — see capture-manifest.txt there"
        return 0
    fi
    _failcap_note "[INFO]  fail-time capture (${reason}) -> ${dir}"
}

_failcap_armed() { [ -n "${SUITE_FAILCAP_DIR:-}" ] && [ -d "${SUITE_FAILCAP_DIR}" ] && [ -z "${_FAILCAP_ACTIVE:-}" ]; }

# Called by log_fail after it has recorded the failure.
_failcap_on_fail() { _failcap_on_fail_body "$@" || true; }
_failcap_on_fail_body() {
    _failcap_armed || return 0
    : > "${SUITE_FAILCAP_DIR}/suite-has-fail" 2>/dev/null || true
    local n
    n=$(ls -d "${SUITE_FAILCAP_DIR}"/first-fail-* 2>/dev/null | wc -l | tr -d ' ')
    if [ "${n:-0}" -ge "$FAILCAP_MAX_FIRST_FAIL" ]; then
        mkdir "${SUITE_FAILCAP_DIR}/cap-warned" 2>/dev/null \
            && _failcap_note "[WARN]  fail-time capture: cap of ${FAILCAP_MAX_FIRST_FAIL} first-fail captures reached for this suite — later failing tests are not captured"
        return 0
    fi
    # mkdir is the atomic once-per-test latch and, unlike a shell variable, holds across subshells.
    mkdir "${SUITE_FAILCAP_DIR}/first-fail-${SUITE_TAG:-no-suite}-${TEST_TAG:-outside-a-test}" 2>/dev/null || return 0
    _failcap_capture "first-fail"
    return 0
}

# capture_before_destructive <step> — evidence from the outgoing cluster, taken before <step> recreates it.
capture_before_destructive() { _failcap_before_destructive_body "$@" || true; }
_failcap_before_destructive_body() {
    local step="${1:-unnamed}" n
    _failcap_armed || return 0
    [ -f "${SUITE_FAILCAP_DIR}/suite-has-fail" ] || [ "${TEST_FAIL_COUNT:-0}" -gt 0 ] || return 0
    n=$(ls -d "${SUITE_FAILCAP_DIR}"/pre-* 2>/dev/null | wc -l | tr -d ' ')
    if [ "${n:-0}" -ge "$FAILCAP_MAX_PRE_DESTRUCTIVE" ]; then
        _failcap_note "[WARN]  capture_before_destructive(${step}): cap of ${FAILCAP_MAX_PRE_DESTRUCTIVE} pre-destructive captures reached for this suite — not capturing"
        return 0
    fi
    mkdir "${SUITE_FAILCAP_DIR}/pre-$((n + 1))-${step}" 2>/dev/null || return 0
    _failcap_capture "before-${step}"
    return 0
}
