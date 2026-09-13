#!/bin/bash
# run-stub-suites.sh — runs every test-*.sh stub suite in this directory; the CI `stub-suites` job
# (#1060) calls it on every PR and push. The suites stub ssh/hcloud/curl/api_get, so no cluster,
# cloud credential or docker is involved.
#
#   bash aether/tests/integration/test/run-stub-suites.sh
#
# A suite counts as passed only when it exits 0 AND its own closing summary says `failed: 0` with
# `passed:` above zero. Exit status alone is not enough: a sourced helper that calls `exit 0` ends a
# suite before any assertion runs, and that exit is indistinguishable from a clean pass. An empty glob
# exits 2, because a run that found no suites examined nothing and has no verdict to report.
#
# Each suite runs as the leader of its own process group, and a suite still running after
# STUB_SUITE_TIMEOUT_SECONDS (default 300) has the WHOLE GROUP killed, TERM then KILL. The slowest
# suite measured is test-chaos-harness.sh: 21s on a quiet machine and 59s at load average 34, so the
# 300s default gives about 5x headroom over the contended figure. Killing only
# the top-level pid is not enough: a stub that recursed without bound once leaked 2,342 bash
# processes on a shared machine (#1060). A timeout alone is too slow against recursion: a guarded
# run of that shape still reached 2,247 processes before its timeout fired. So the watchdog also
# counts the group every second and kills it once it exceeds STUB_SUITE_MAX_PROCS (default 300). If
# the count cannot be read (a fork failed, which is what an exhausted process table looks like),
# that is treated as exceeding the ceiling. Timing and killing use only builtins (read -t, kill,
# SECONDS). A suite that calls setsid itself leaves the group and is outside this guard.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SUITE_TIMEOUT="${STUB_SUITE_TIMEOUT_SECONDS:-300}"
MAX_PROCS="${STUB_SUITE_MAX_PROCS:-300}"

suites=()
for s in "$SCRIPT_DIR"/test-*.sh; do
    [ -f "$s" ] && suites+=("$s")
done
if [ ${#suites[@]} -eq 0 ]; then
    echo "run-stub-suites: no test-*.sh under ${SCRIPT_DIR}, so this run EXAMINED NOTHING; refusing to report success" >&2
    exit 2
fi
for tool in perl pgrep mkfifo; do
    if ! command -v "$tool" > /dev/null 2>&1; then
        echo "run-stub-suites: '${tool}' is required for process-group isolation and the process ceiling; refusing to run suites unguarded" >&2
        exit 2
    fi
done

# A one-second tick that forks nothing: read -t on a FIFO that nobody writes.
tick_dir="$(mktemp -d)"
mkfifo "${tick_dir}/tick"
exec 9<>"${tick_dir}/tick"
trap 'rm -rf "$tick_dir"' EXIT
tick() { read -r -t "${1:-1}" -u 9 _ || true; }

# group_alive <pgid>: true while any process is left in the group.
group_alive() { kill -0 -- "-$1" 2>/dev/null; }

# kill_group <pgid>: TERM, a 2s grace, then KILL, for every process in the group.
kill_group() {
    kill -TERM -- "-$1" 2>/dev/null
    tick 2
    kill -KILL -- "-$1" 2>/dev/null
}

# run_suite <suite> <output file>: sets RC, KILLED (empty, "timeout" or "runaway"), MEMBERS (group
# size when killed), PEAK (largest group size sampled) and LEFT (processes still in the group after
# the kill, or strays a normally-exiting suite left behind).
run_suite() {
    local suite="$1" out="$2"
    perl -e 'setpgrp(0, 0) or die "setpgrp: $!\n"; exec @ARGV or die "exec: $!\n"' bash "$suite" > "$out" 2>&1 < /dev/null &
    local pgid=$! start=$SECONDS n members
    SUITE_PGID=$pgid
    KILLED=""; MEMBERS=""; PEAK=0; LEFT=0
    while kill -0 "$pgid" 2>/dev/null; do
        if members="$(pgrep -g "$pgid" 2>/dev/null)"; then
            set -- $members
            n=$#
        else
            # pgrep exits 1 when the group is empty (the suite ended between checks); anything else,
            # including a failed fork, means the count is unreadable.
            [ $? -eq 1 ] && n=0 || n=$((MAX_PROCS + 1))
        fi
        (( n > PEAK )) && PEAK=$n
        if (( n > MAX_PROCS )); then
            KILLED="runaway"; MEMBERS=$n
            kill_group "$pgid"
            break
        fi
        if (( SECONDS - start >= SUITE_TIMEOUT )); then
            KILLED="timeout"; MEMBERS=$n
            kill_group "$pgid"
            break
        fi
        tick 1
    done
    wait "$pgid" 2>/dev/null
    RC=$?
    if group_alive "$pgid"; then
        kill -KILL -- "-$pgid" 2>/dev/null
        for n in 1 2 3 4 5; do
            group_alive "$pgid" || break
            tick 1
        done
        group_alive "$pgid" && LEFT=1
        [ -z "$KILLED" ] && LEFT=1
    fi
    return 0
}

results=()
bad=0
for s in "${suites[@]}"; do
    name="$(basename "$s")"
    out="$(mktemp)"
    echo "=== ${name}"
    suite_start=$SECONDS
    run_suite "$s" "$out"
    SUITE_SECS=$((SECONDS - suite_start))
    # Name and process group per suite, so a later step can check a ps snapshot against them.
    [ -n "${STUB_SUITE_PGID_FILE:-}" ] && echo "${name} ${SUITE_PGID}" >> "$STUB_SUITE_PGID_FILE"
    if [ -n "$KILLED" ]; then
        tail -n 40 "$out"
    else
        cat "$out"
    fi
    passed="$(sed -n 's/^  passed: \([0-9][0-9]*\)$/\1/p' "$out" | tail -1)"
    failed="$(sed -n 's/^  failed: \([0-9][0-9]*\)$/\1/p' "$out" | tail -1)"
    rm -f "$out"
    if [ -n "$KILLED" ]; then
        if [ "$KILLED" = "runaway" ]; then
            why="runaway: ${MEMBERS} processes in its group exceeded the ceiling of ${MAX_PROCS}"
        else
            why="timed out after ${SUITE_TIMEOUT}s with ${MEMBERS} processes in its group"
        fi
        if [ "$LEFT" -eq 1 ]; then
            verdict="FAIL (${why}; killed the whole group, but processes SURVIVED the kill)"
        else
            verdict="FAIL (${why}; killed the whole group, none left)"
        fi
    elif [ "$LEFT" -eq 1 ]; then
        verdict="FAIL (exit ${RC} but it left processes running in its group; killed them)"
    elif [ "$RC" -ne 0 ]; then
        verdict="FAIL (exit ${RC})"
    elif [ -z "$passed" ] || [ -z "$failed" ]; then
        verdict="FAIL (exit 0 but no passed/failed summary: the suite ended before reporting)"
    elif [ "$failed" -ne 0 ]; then
        verdict="FAIL (exit 0 but its summary reports failed: ${failed})"
    elif [ "$passed" -eq 0 ]; then
        verdict="FAIL (exit 0 but passed: 0, so it asserted nothing)"
    else
        verdict="ok"
    fi
    [ "$verdict" = "ok" ] || bad=$((bad + 1))
    results+=("${name}  exit=${RC} passed=${passed:-?} failed=${failed:-?} peak-procs=${PEAK} secs=${SUITE_SECS}  ${verdict}")
done

echo ""
echo "=== stub suites: ${#suites[@]} run, ${bad} not passed (per-suite limits: ${SUITE_TIMEOUT}s, ${MAX_PROCS} processes)"
printf '  %s\n' "${results[@]}"
[ "$bad" -eq 0 ]
