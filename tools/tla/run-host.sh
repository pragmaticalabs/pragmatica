#!/usr/bin/env bash
# Run, on THIS host, the rows a GitHub-hosted runner cannot take (#3636): the rows run.sh reports as SKIPPED-HOST.
# Usage: tools/tla/run-host.sh <full|heavy> [module-regex] [variant-regex]     (from the repository root; on a build host)
# The rows are the ones `TLA_HOST=list run.sh` names: budgets.tsv rows over its @hosted limit, in the profile (heavy: only the rows
# marked heavy). They run one after another, each through `TLA_HOST=1 run.sh`, so each runs under ITS OWN time cap and -Xmx from
# budgets.tsv and appends the same evidence row run.sh writes (TLA_RESULTS, default $TLA_OUT/results.tsv, profile column = the
# profile named here). Env knobs of run.sh (TLA_JAR, TLC_WORKERS, TLA_OUT, TLC_TIMEOUT, BUDGETS_TSV, MODELS_TSV ...) pass through.
# Memory precheck, before every row: MemAvailable (MiB, from TLA_MEMINFO, default /proc/meminfo) must be >= the row's heap + 2048
# MiB (the heap, plus the resource guard's own floor; a row with the JVM default heap needs 2048 + 2048). Otherwise the script
# waits, polling every TLA_HOST_POLL_SECS (default 60) for up to TLA_HOST_WAIT_SECS (default 3600), and a row that still has not
# room is NOT started: it is printed as `NOT-STARTED <module>/<variant>` with the numbers, counted, and the exit code is 1. Never
# a start "to see what happens": TLC out of memory is not a violation, and the OOM killer takes the other jobs of the host.
# One TLC run per lane per host (workspace rule): this script is sequential by construction; do not start it twice on a host.
# Exit 0: every listed row ran, wrote an evidence row, and run.sh was green for it (or no row is over the hosted limit). Exit 1: a row was red, an ERROR, a
# RESOURCE kill or not started. Exit 2: an unusable invocation.
set -uo pipefail
profile=${1:-}; module=${2:-.*}; variant=${3:-.*}
[[ $profile == full || $profile == heavy ]] || { echo "run-host: ERROR profile must be full or heavy, got '$profile'" >&2; exit 2; }
here=$(cd "$(dirname "$0")" && pwd)
meminfo=${TLA_MEMINFO:-/proc/meminfo}
poll=${TLA_HOST_POLL_SECS:-60}; waitmax=${TLA_HOST_WAIT_SECS:-3600}
for v in "$poll" "$waitmax"; do [[ $v =~ ^[0-9]+$ ]] || { echo "run-host: ERROR TLA_HOST_POLL_SECS and TLA_HOST_WAIT_SECS must be whole numbers, got '$v'" >&2; exit 2; }; done
[[ -r $meminfo ]] || { echo "run-host: ERROR cannot read $meminfo: no memory precheck, so no run" >&2; exit 2; }
avail_mb() { awk '/^MemAvailable:/ { print int($2 / 1024) }' "$meminfo"; }
listed=$(TLA_HOST=list bash "$here/run.sh" "$profile" "$module" "$variant") || { echo "run-host: ERROR listing the rows failed" >&2; exit 2; }
if [[ -z $listed ]]; then echo "run-host: profile=$profile: no row over the hosted limit matches '$module' / '$variant'; nothing to run"; exit 0; fi
results=${TLA_RESULTS:-${TLA_OUT:-target/tla}/results.tsv}
# evrows <module> <variant>: how many evidence rows the results file holds for the row (run-host fails a row that adds none)
evrows() { [[ -f $results ]] && awk -F'\t' -v m="$1" -v v="$2" 'NR > 1 && $5 == m && $6 == v { n++ } END { print n + 0 }' "$results" || echo 0; }
total=$(wc -l <<<"$listed" | tr -d ' '); green=0; red=0; notstarted=0; started=$(date +%s)
echo "run-host: profile=$profile rows=$total (budgets from ${BUDGETS_TSV:-$here/budgets.tsv})"
while IFS=$'\t' read -r row timeout heap; do
  m=${row%%/*}; v=${row#*/}
  if [[ $heap == - ]]; then need=4096; else need=$(( ${heap%g} * 1024 + 2048 )); fi
  waited=0; have=$(avail_mb)
  while [[ ! $have =~ ^[0-9]+$ ]] || (( have < need )); do
    if (( waited >= waitmax )); then break; fi
    echo "run-host: waiting for memory before $row: MemAvailable ${have:-unreadable} MB < ${need} MB (heap ${heap} + 2048); ${waited}s of ${waitmax}s"
    sleep "$poll"; waited=$((waited + poll)); have=$(avail_mb)
  done
  if [[ ! $have =~ ^[0-9]+$ ]] || (( have < need )); then
    echo "NOT-STARTED $row budget ${timeout}s/${heap}: MemAvailable ${have:-unreadable} MB < ${need} MB after waiting ${waited}s"
    notstarted=$((notstarted + 1)); continue
  fi
  echo "run-host: starting $row under ${timeout}s/${heap} (MemAvailable ${have} MB >= ${need} MB)"
  before=$(evrows "$m" "$v")
  if TLA_HOST=1 bash "$here/run.sh" "$profile" "$m" "$v" </dev/null; then ok=1; else ok=0; fi
  if [[ $(evrows "$m" "$v") == "$before" ]]; then
    echo "run-host: RED $row: run.sh wrote no evidence row to $results, so nothing ran for it; not green"; ok=0
  fi
  if [[ $ok == 1 ]]; then green=$((green + 1)); else red=$((red + 1)); fi
done <<<"$listed"
echo "run-host: profile=$profile rows=$total green=$green red=$red not-started=$notstarted wall=$(( $(date +%s) - started ))s $([[ $red == 0 && $notstarted == 0 ]] && echo GREEN || echo RED)"
[[ $red == 0 && $notstarted == 0 ]]
