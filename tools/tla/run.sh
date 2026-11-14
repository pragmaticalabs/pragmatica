#!/usr/bin/env bash
# Run every model config listed in models.tsv and compare each verdict with the expected one.
# Usage: tools/tla/run.sh [small|full|sim] [module-regex]     (run from the repository root)
#   small: the config's constants with the row's overrides; full: every config as written;
#   sim:   every config as written, sampled by TLC's random simulation (-simulate) under a time budget per config.
#          Simulation is NOT a proof: a "pass" row means no violation among the sampled traces, nothing more. It is the
#          cheap tripwire for the known-bad rows (each must still FAIL) and a timing/trace-count reference.
# Columns of models.tsv: module root variant expect property small what.
# A "pass" row must finish with no violation (TLC exit 0). A "fail" row is a known-bad config (an earlier version of
# the code, or a filed defect): it must end in a violation (TLC exit 12 safety, 13 liveness) of one of the properties
# named in its `property` column (comma-separated list; the violated one must be in it), so a different violation, or
# a "fail" that is really a TLC error, cannot satisfy it. If a known-bad config passes, the model no longer reaches the
# code it pins, and the run is red. Any other TLC exit (parse, config or semantic error, timeout) reads ERROR and is
# red. Every config the extractor finds must have a row, so no config is skipped silently.
# Every row prints its wall time; a sim row also prints the trace count and mean trace length of TLC's last progress line.
# Env: TLA_JAR, TLC_WORKERS, TLC_OPTS (passed to check.sh), TLA_OUT (default target/tla), TLC_TIMEOUT (seconds per
# config, needs coreutils timeout), MODELS_TSV (default: models.tsv beside this script; used by runselftest.sh),
# SIM_SECONDS (time budget per config, default 60), SIM_TRACES (trace cap, default 1000000), SIM_DEPTH (default 40; at 200 the Stalled "settled" row was not found in 60 s, at 40 it is found in seconds).
set -uo pipefail
profile=${1:-small}; filter=${2:-.*}
here=$(cd "$(dirname "$0")" && pwd)
models=${MODELS_TSV:-$here/models.tsv}
outroot=${TLA_OUT:-target/tla}
[[ $profile == small || $profile == full || $profile == sim ]] || { echo "run: ERROR profile must be small, full or sim, got '$profile'" >&2; exit 2; }
rows=$(grep -vE '^[[:space:]]*(#|$)' "$models" | awk -F'\t' -v f="$filter" '$1 ~ "^(" f ")$"')
[[ -n $rows ]] || { echo "run: ERROR no model matches '$filter'" >&2; exit 2; }
bad=$(awk -F'\t' '($4 != "pass" && $4 != "fail") || ($4 == "fail" && ($5 == "" || $5 == "-")) { print $1 "/" $3 }' <<<"$rows")
[[ -z $bad ]] || { echo "run: ERROR models.tsv rows need expect pass|fail and, for fail, the violated property: $bad" >&2; exit 2; }
red=0; summary=(); runstart=$(date +%s)
if [[ $profile == sim ]]; then
  export TLC_OPTS="${TLC_OPTS:-} -simulate num=${SIM_TRACES:-1000000} -depth ${SIM_DEPTH:-40}"
  export TLC_JAVA_OPTS="${TLC_JAVA_OPTS:-} -Dtlc2.TLC.stopAfter=${SIM_SECONDS:-60}"
  echo "run: sim profile: random simulation, ${SIM_SECONDS:-60}s per config, depth ${SIM_DEPTH:-40}; a sampled pass is NOT a proof"
fi

# Coverage: the configs the extractor writes for each module must be exactly the rows listed for it.
for module in $(cut -f1 <<<"$rows" | sort -u); do
  root=$(awk -F'\t' -v m="$module" '$1 == m { print $2; exit }' <<<"$rows")
  python3 "$here/extract.py" --module "$module" --out "$outroot/$module" --base . "$root" >/dev/null || { echo "run: ERROR extract $module" >&2; exit 2; }
  found=$(cd "$outroot/$module" && ls "${module}"_*.cfg | sed -E "s/^${module}_(.*)\.cfg$/\1/" | sort)
  listed=$(awk -F'\t' -v m="$module" '$1 == m { print $3 }' <<<"$rows" | sort)
  if [[ $found != "$listed" ]]; then
    echo "run: RED $module configs and models.tsv rows differ (< extracted, > listed):"
    diff <(echo "$found") <(echo "$listed") | grep -E '^[<>]'
    red=1
  fi
done

while IFS=$'\t' read -r module root variant expect property small _; do
  consts=""; [[ $profile == small && $small != "-" ]] && consts=${small//,/ }
  start=$(date +%s)
  if [[ -n ${TLC_TIMEOUT:-} ]] && command -v timeout >/dev/null; then
    TLC_CONST=$consts TLA_OUT="$outroot/$module" timeout "$TLC_TIMEOUT" bash "$here/check.sh" "$module" "$variant" "$root" > "$outroot/$module/run_${variant}.txt" 2>&1
  else
    TLC_CONST=$consts TLA_OUT="$outroot/$module" bash "$here/check.sh" "$module" "$variant" "$root" > "$outroot/$module/run_${variant}.txt" 2>&1
  fi
  rc=$?
  secs=$(( $(date +%s) - start ))
  log="$outroot/$module/${module}_${variant}.log"
  violated=$(sed -nE 's/^Error: (Invariant|Temporal property|Action property) ([A-Za-z0-9_]+) (is|was) violated.*/\2/p' "$log" 2>/dev/null | head -1)
  case $rc in
    0) got=pass ;;
    12|13) got=fail ;;
    *) got="error(exit $rc)" ;;
  esac
  note=""
  if [[ $got == fail ]]; then
    if [[ ,$property, == *,"$violated",* && -n $violated ]]; then note=" violated=$violated"
    else got="fail(violated=${violated:-none}, expected one of ${property})"; fi
  fi
  if [[ $got == error* ]]; then verdict=ERROR; red=1
  elif [[ $got == "$expect" ]]; then verdict=OK
  else verdict=RED; red=1; fi
  states=$(grep -oE '[0-9,]+ distinct states found' "$log" 2>/dev/null | tail -1)
  simstat=""
  if [[ $profile == sim ]]; then
    simstat=$(grep -E '^Progress:.*traces generated' "$log" 2>/dev/null | tail -1 | sed -E 's/^Progress: ([0-9]+) states checked, ([0-9]+) traces generated \(trace length: mean=([0-9]+).*/traces=\2 mean-length=\3 states=\1/')
    [[ -n $simstat ]] || simstat="no trace count"
  fi
  line="$verdict $module/$variant expect=$expect got=$got${note} ${secs}s ${simstat:-${states:-no state count}} ${consts:+[$consts]}"
  echo "$line"; summary+=("$line")
  [[ $verdict != OK ]] && tail -20 "$outroot/$module/run_${variant}.txt" | sed 's/^/    /'
done <<<"$rows"

echo "run: profile=$profile models=$filter configs=${#summary[@]} wall=$(( $(date +%s) - runstart ))s $([[ $red == 0 ]] && echo GREEN || echo RED)$([[ $profile == sim ]] && echo ' (simulation: sampled, not a proof)')"
exit $red
