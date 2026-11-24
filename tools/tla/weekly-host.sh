#!/bin/bash
# The weekly host-only TLC run (#3659): starts `run-host.sh full` on a build host and turns what it leaves into a verdict line.
# Usage: tools/tla/weekly-host.sh start|collect      (from anywhere; run by launchd on the Mac, see install-weekly.sh)
# Why the Mac: the build hosts cannot ssh each other (no name resolution, no keys), so only the Mac can pick the least-loaded one.
#   start    Sunday 02:00 CET. Refuses while a run is pending. Probes cloudbb-5..8 (one ssh each: load, cores, a live TLC run), skips
#            a host that is unreachable or already runs TLC (`pgrep -u aether -f tla2tools`: one TLC run per host, never beside a
#            lane's), picks the lowest load per core, checks out the rc6 head on it (bb-checkout) and launches run-host.sh full
#            detached there. Writes run.json. No usable host => a NOT-GREEN verdict row (reason no-free-host), never silence.
#   collect  every 10 min; a no-op unless a run is pending. When the run's weekly.done marker exists it pulls log + results,
#            derives the verdict with weekly-verdict.py (zero rows is never GREEN), appends ONE row to verdict.tsv and removes
#            the remote checkout. A run without a marker after TLA_WEEKLY_STALL_SECS (default 172800 = 48 h: the four rows' caps sum to 108000 s, plus up to 3600 s of memory wait each) becomes NOT-GREEN reason=stalled.
# This script writes no tracker, no event: the router reads verdict.tsv (sweep-state/tlc_weekly.py): TLC-RED / TLC-NOT-GREEN, and
# TLC-MISSING when no verdict row is newer than 8 days. The scheduler is not the thing that notices it did not run.
# State dir (TLA_WEEKLY_DIR, default ~/IdeaProjects/oss/internal/sweep-state/tlc-weekly): run.json, verdict.tsv, runs/<id>/{log,results}.
# verdict.tsv columns: date  epoch  sha  host  status  rows  red-rows  reason     (status GREEN | RED | NOT-GREEN)
# Test knobs: TLA_WEEKLY_SSH (the ssh command; the selftest puts a fake), TLA_WEEKLY_HOSTS, TLA_WEEKLY_SHA (skip git ls-remote),
# TLA_WEEKLY_NOW (epoch). The remote protocol is ssh commands only (no scp), so one fake answers all of it.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
dir=${TLA_WEEKLY_DIR:-$HOME/IdeaProjects/oss/internal/sweep-state/tlc-weekly}
ssh_cmd=${TLA_WEEKLY_SSH:-ssh}
hosts=${TLA_WEEKLY_HOSTS:-cloudbb-5 cloudbb-6 cloudbb-7 cloudbb-8}
stall=${TLA_WEEKLY_STALL_SECS:-172800}
repo_url=${TLA_WEEKLY_REPO:-https://github.com/pragmaticalabs/pragmatica.git}
now() { echo "${TLA_WEEKLY_NOW:-$(date +%s)}"; }
iso() { date -u -r "$1" +%FT%TZ 2>/dev/null || date -u -d "@$1" +%FT%TZ; }
runjson=$dir/run.json
get() { sed -n "s/^$1=//p" "$runjson" | head -1; }
rssh() { "$ssh_cmd" -o BatchMode=yes -o ConnectTimeout=10 "$@"; }
# verdict_row <sha> <host> <status> <rows> <red-rows> <reason>
verdict_row() { local t; t=$(now); printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(iso "$t")" "$t" "$1" "$2" "$3" "$4" "$5" "$6" >> "$dir/verdict.tsv"; }
mkdir -p "$dir/runs"

# probe <host>: prints "<load per core> <free|busy>" or nothing when unreachable
probe() {
  local out load cores busy
  out=$(rssh "$1" 'cut -d" " -f1 /proc/loadavg; nproc; if pgrep -u aether -f "[t]la2tools" >/dev/null; then echo busy; else echo free; fi') || return 0
  load=$(sed -n 1p <<<"$out"); cores=$(sed -n 2p <<<"$out"); busy=$(sed -n 3p <<<"$out")
  [[ $load =~ ^[0-9.]+$ && $cores =~ ^[0-9]+$ && $busy =~ ^(free|busy)$ ]] || return 0
  awk -v l="$load" -v c="$cores" -v b="$busy" 'BEGIN { printf "%.4f %s\n", l / c, b }'
}

start() {
  [[ -f $runjson ]] && { echo "weekly-host: a run is pending ($(get host) $(get id)); not starting a second one"; return 0; }
  local sha=${TLA_WEEKLY_SHA:-} best= bestload= h p
  [[ -n $sha ]] || sha=$(git ls-remote "$repo_url" refs/heads/release-1.0.0-rc6 | cut -f1)
  [[ $sha =~ ^[0-9a-f]{40}$ ]] || { verdict_row "-" "-" NOT-GREEN 0 "" no-sha; echo "weekly-host: cannot resolve the rc6 head"; return 1; }
  for h in $hosts; do
    p=$(probe "$h")
    [[ -n $p ]] || { echo "weekly-host: $h unreachable, skipped"; continue; }
    [[ ${p#* } == free ]] || { echo "weekly-host: $h runs TLC already, skipped"; continue; }
    if [[ -z $best ]] || awk -v a="${p% *}" -v b="$bestload" 'BEGIN { exit !(a < b) }'; then best=$h; bestload=${p% *}; fi
  done
  [[ -n $best ]] || { verdict_row "$sha" "-" NOT-GREEN 0 "" no-free-host; echo "weekly-host: no free host; NOT-GREEN row written"; return 1; }
  local id co; id=tlcw-$(date -u +%Y%m%d%H%M); co=work/co/$id
  if ! rssh "$best" "BB_OWNER=tlcw ~/tools/bb-checkout $id $sha >/dev/null 2>&1 && cd ~/$co && setsid -f bash -c 'TLA_JAR=\$HOME/tools/tla/tla2tools.jar TLA_RESULTS=\$PWD/weekly-results.tsv TLA_OUT=\$PWD/target/tla bash tools/tla/run-host.sh full > weekly.log 2>&1; echo \$? > weekly.done' </dev/null >/dev/null 2>&1"; then
    verdict_row "$sha" "$best" NOT-GREEN 0 "" launch-failed; echo "weekly-host: launching on $best failed; NOT-GREEN row written"; return 1
  fi
  printf 'id=%s\nhost=%s\nsha=%s\nco=%s\nstarted=%s\n' "$id" "$best" "$sha" "$co" "$(now)" > "$runjson"
  echo "weekly-host: started $id on $best (load/core $bestload) at ${sha:0:10}"
}

collect() {
  [[ -f $runjson ]] || return 0
  local id host sha co started rc out
  id=$(get id); host=$(get host); sha=$(get sha); co=$(get co); started=$(get started)
  if rc=$(rssh "$host" "cat ~/$co/weekly.done" 2>/dev/null) && [[ $rc =~ ^[0-9]+$ ]]; then
    mkdir -p "$dir/runs/$id"
    rssh "$host" "cat ~/$co/weekly.log" > "$dir/runs/$id/log" 2>/dev/null
    rssh "$host" "cat ~/$co/weekly-results.tsv" > "$dir/runs/$id/results" 2>/dev/null
    out=$(python3 "$here/weekly-verdict.py" "$dir/runs/$id/log" "$dir/runs/$id/results")
    status=$(cut -f1 <<<"$out"); rows=$(cut -f2 <<<"$out"); red=$(cut -f3 <<<"$out"); why=$(cut -f4 <<<"$out")   # cut, not read: read collapses the empty red field
    verdict_row "$sha" "$host" "$status" "${rows:-0}" "$red" "$why"
    rssh "$host" "rm -rf ~/$co" >/dev/null 2>&1 || true
    rm -f "$runjson"; echo "weekly-host: $id on $host: $status rows=$rows $red"
  elif (( $(now) - started > stall )); then
    verdict_row "$sha" "$host" NOT-GREEN 0 "" stalled; rm -f "$runjson"; echo "weekly-host: $id on $host: no result after ${stall}s; NOT-GREEN stalled (checkout left on the host)"
  fi
}

case "${1:-}" in
  start) start ;;
  collect) collect ;;
  *) sed -n '2,3p' "$0" >&2; exit 2 ;;
esac
