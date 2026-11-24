#!/usr/bin/env bash
# Run every model config listed in models.tsv and compare each verdict with the expected one.
# Usage: tools/tla/run.sh [small|full|sim|heavy] [module-regex] [variant-regex]     (run from the repository root)
#   variant-regex selects the rows to model-check (to split one module across hosts); coverage still checks every row.
#   small: the config's constants with the row's overrides; full: every config as written;
#   heavy: only the rows marked `heavy` in models.tsv (the small column holds `heavy`), as written. A heavy row is one that
#          does not finish inside the small profile's per-config budget (exhaustive at tens of millions of states): `small`
#          does NOT run it and does NOT count it as a pass; it prints `SKIPPED-HEAVY <module>/<variant>` and writes a
#          SKIPPED-HEAVY evidence row (actual=skipped), so the skip is named in the rows and in results-check.py's output.
#          full and sim run heavy rows like any other (as written); heavy runs them alone, for the nightly/on-demand job.
#   sim:   every config as written, sampled by TLC's random simulation (-simulate) under a time budget per config.
#          Simulation is NOT a proof: a "pass" row means no violation among the sampled traces, nothing more. It is the
#          cheap tripwire for the known-bad rows (each must still FAIL) and a timing/trace-count reference.
# Columns of models.tsv: module root variant expect property small what.
# A "pass" row must finish with no violation (TLC exit 0). A "fail" row is a known-bad config (an earlier version of
# the code, or a filed defect): it must end in a violation (TLC exit 12 safety, 13 liveness) of one of the properties
# named in its `property` column (comma-separated list; the violated one must be in it), so a different violation, or
# a "fail" that is really a TLC error, cannot satisfy it. If a known-bad config passes, the model no longer reaches the
# code it pins, and the run is red. Any other TLC exit (parse, config or semantic error, timeout) reads ERROR and is
# red. Exit 75 is check.sh's resource guard (TLC killed for disk >= TLA_DISK_MAX percent or MemAvailable < TLA_MEM_MIN_MB):
# it reads RESOURCE (actual=error in the evidence row), red, never a pass or a fail, with the RESOURCE-GUARD line as the note.
# Every config the extractor finds must have a row, so no config is skipped silently.
# Every row prints its wall time; a sim row also prints the trace count and mean trace length of TLC's last progress line.
# Per-row budgets (#3636): the full and heavy profiles run every row under ITS OWN time cap and heap, read from budgets.tsv
# (module variant timeout_s heap; the exact row, else the `* *` default row). The cap is coreutils `timeout` (run.sh exits 2 where
# there is none: a budget that cannot be enforced is not run); the heap is `-Xmx<heap>` put in front of TLC_JAVA_OPTS, which check.sh
# passes to java (a later -Xmx in the env wins). A row that outlives its cap is verdict ERROR (exit 124) and the line names the cap
# and where it came from. A row over the hosted-runner limit (budgets.tsv `@hosted`) is NOT run by default: it prints
# `SKIPPED-HOST <module>/<variant>` and records a SKIPPED-HOST evidence row (actual=skipped), never a pass; tools/tla/run-host.sh
# runs those rows on a build host (TLA_HOST=1: run only them; TLA_HOST=list: print them and exit). small and sim ignore budgets.tsv.
# Env: TLA_JAR, TLC_WORKERS, TLC_OPTS (passed to check.sh), TLA_OUT (default target/tla), TLC_TIMEOUT (seconds per
# config, needs coreutils timeout; overrides the budget of every row), BUDGETS_TSV (default: budgets.tsv beside this script), TLA_HOST (see above), MODELS_TSV (default: models.tsv beside this script; used by runselftest.sh),
# SIM_SECONDS (time budget per config, default 60), SIM_TRACES (trace cap, default 1000000), SIM_DEPTH (default 40; at 200 the Stalled "settled" row was not found in 60 s, at 40 it is found in seconds),
# PINS_TSV (default: pinned-counts.tsv beside this script).
# Evidence rows: every config that ran appends one row to TLA_RESULTS (default: $TLA_OUT/results.tsv; a PR that touches a
# modelled region commits its run's rows to tools/tla/results.tsv, README "Evidence rows"). One TSV with a header:
#   commit dirty date profile module variant expect actual violated distinct tlc verdict constants
# commit = HEAD of the repository run.sh is started in ("none" outside a git repository); dirty = "dirty" when tracked
# files differed from HEAD when the run started (the commit then does not name the code that ran), "clean", or "unknown";
# actual = pass | fail | error from TLC's exit code, or skipped for a heavy row that small did not run or a row over the hosted
# budget (the verdict column, OK | RED | ERROR | RESOURCE | SKIPPED-HEAVY | SKIPPED-HOST, also reads the violated property and the pinned count); distinct = TLC's distinct-state count; tlc = TLC's version; date = UTC end of the row.
# A results file with another header is refused (exit 2), never appended to.
# Pinned counts (pinned-counts.tsv: module variant profile distinct why): a listed pass row must also find exactly that
# many distinct states at that profile, or it is RED. A spec that extends a model by a mode switch must leave the old
# mode's state space unchanged; the pin makes an extension that leaks into the old mode go red, which a verdict alone
# cannot see when the leak only removes states (#3443: the leak made two known-bad controls pass).
set -uo pipefail
profile=${1:-small}; filter=${2:-.*}; vfilter=${3:-.*}
here=$(cd "$(dirname "$0")" && pwd)
models=${MODELS_TSV:-$here/models.tsv}
pins=${PINS_TSV:-$here/pinned-counts.tsv}
outroot=${TLA_OUT:-target/tla}
[[ $profile == small || $profile == full || $profile == sim || $profile == heavy ]] || { echo "run: ERROR profile must be small, full, sim or heavy, got '$profile'" >&2; exit 2; }
rows=$(grep -vE '^[[:space:]]*(#|$)' "$models" | awk -F'\t' -v f="$filter" '$1 ~ "^(" f ")$"')
[[ -n $rows ]] || { echo "run: ERROR no model matches '$filter'" >&2; exit 2; }
bad=$(awk -F'\t' '($4 != "pass" && $4 != "fail") || ($4 == "fail" && ($5 == "" || $5 == "-")) { print $1 "/" $3 }' <<<"$rows")
[[ -z $bad ]] || { echo "run: ERROR models.tsv rows need expect pass|fail and, for fail, the violated property: $bad" >&2; exit 2; }
pinrows=$( { [[ -f $pins ]] && grep -vE '^[[:space:]]*(#|$)' "$pins"; } | awk -F'\t' -v f="$filter" '$1 ~ "^(" f ")$"')
if [[ -n $pinrows ]]; then
  badpin=$(awk -F'\t' 'NR == FNR { if ($4 == "pass") ok[$1 "/" $3] = 1; next }
    !(($1 "/" $2) in ok) || ($3 != "small" && $3 != "full") || $4 !~ /^[0-9]+$/ { print $1 "/" $2 "/" $3 }' <(echo "$rows") <(echo "$pinrows"))
  [[ -z $badpin ]] || { echo "run: ERROR $pins rows need a models.tsv pass row, profile small|full and a digit count: $badpin" >&2; exit 2; }
fi
awk -F'\t' -v f="$vfilter" '$3 ~ "^(" f ")$" { n++ } END { exit n == 0 }' <<<"$rows" || { echo "run: ERROR no row matches variant '$vfilter'" >&2; exit 2; }
if [[ $profile == heavy ]]; then
  awk -F'\t' -v f="$vfilter" '$3 ~ "^(" f ")$" && $6 == "heavy" { n++ } END { exit n == 0 }' <<<"$rows" || { echo "run: ERROR no row marked heavy matches '$filter' / '$vfilter'" >&2; exit 2; }
fi
budgets=${BUDGETS_TSV:-$here/budgets.tsv}
host=${TLA_HOST:-}
[[ -z $host || $host == 1 || $host == list ]] || { echo "run: ERROR TLA_HOST must be unset, 1 or list, got '$host'" >&2; exit 2; }
use_budgets=0; [[ $profile == full || $profile == heavy ]] && use_budgets=1
hosted_t=0; hosted_h=0
if [[ $use_budgets == 1 ]]; then
  [[ -f $budgets ]] || { echo "run: ERROR no budget table $budgets" >&2; exit 2; }
  badb=$(awk -F'\t' 'NR == FNR { if ($0 !~ /^[[:space:]]*(#|$)/) known[$1 "/" $3] = 1; next }
    $0 ~ /^[[:space:]]*(#|$)/ { next }
    NF != 5 { print "row with " NF " columns: " substr($0, 1, 50); next }
    $3 !~ /^[0-9]+$/ || $3 + 0 == 0 { print $1 "/" $2 ": timeout_s must be a positive whole number"; next }
    $4 !~ /^([0-9]+g|-)$/ { print $1 "/" $2 ": heap must be <N>g or -"; next }
    $1 == "*" || $1 == "@hosted" { key = $1; if (key in seen) print "second " key " row"; seen[key] = 1
      if (($1 == "*" && $2 != "*") || ($1 == "@hosted" && ($2 != "limit" || $4 == "-"))) print $1 "/" $2 ": malformed " $1 " row"; next }
    !(($1 "/" $2) in known) { print $1 "/" $2 ": no such row in models.tsv"; next }
    { key = $1 "/" $2; if (key in seen) print "second row " key; seen[key] = 1 }
    END { if (!("*" in seen)) print "no `*` default row"; if (!("@hosted" in seen)) print "no @hosted row" }' "$models" "$budgets")
  [[ -z $badb ]] || { echo "run: ERROR $budgets: $badb" >&2; exit 2; }
  read -r hosted_t hosted_h < <(awk -F'\t' '$1 == "@hosted" { print $3, $4 }' "$budgets")
  hosted_h=${hosted_h%g}
  if [[ $host != list ]] && ! command -v timeout >/dev/null; then echo "run: ERROR profile $profile needs coreutils timeout to enforce the per-row budgets; none on this host" >&2; exit 2; fi
fi
# budget_of <module> <variant>: "<timeout_s> <heap> <source>" of the row (exact row, else the default row)
budget_of() {
  awk -F'\t' -v m="$1" -v v="$2" '$0 ~ /^[[:space:]]*(#|$)/ { next }
    $1 == m && $2 == v { print $3, $4, "budgets.tsv row"; found = 1; exit }
    $1 == "*" { d = $3 " " $4 " default row" }
    END { if (!found) print d }' "$budgets"
}
# over_hosted <timeout_s> <heap>: 0 when a hosted runner takes the row, 1 when it does not
over_hosted() { awk -v t="$1" -v h="$2" -v lt="$hosted_t" -v lh="$hosted_h" 'BEGIN { exit !(t + 0 > lt + 0 || (h != "-" && h + 0 > lh + 0)) }' && echo 1 || echo 0; }
selected=$(awk -F'\t' -v f="$vfilter" -v p="$profile" '$3 ~ "^(" f ")$" && (p != "heavy" || $6 == "heavy")' <<<"$rows")
if [[ $host == list ]]; then
  [[ $use_budgets == 1 ]] || { echo "run: ERROR TLA_HOST=list needs profile full or heavy" >&2; exit 2; }
  while IFS=$'\t' read -r module _ variant _; do
    [[ -n $module ]] || continue
    read -r bt bh _ < <(budget_of "$module" "$variant")
    [[ $(over_hosted "$bt" "$bh") == 1 ]] && printf '%s/%s\t%s\t%s\n' "$module" "$variant" "$bt" "$bh"
  done <<<"$selected"
  exit 0
fi
results=${TLA_RESULTS:-$outroot/results.tsv}
header=$'commit\tdirty\tdate\tprofile\tmodule\tvariant\texpect\tactual\tviolated\tdistinct\ttlc\tverdict\tconstants'
if [[ -s $results ]]; then
  [[ $(head -1 "$results") == "$header" ]] || { echo "run: ERROR $results has a header other than the evidence-row header; not appending" >&2; exit 2; }
else
  mkdir -p "$(dirname "$results")"; printf '%s\n' "$header" > "$results"
fi
if commit=$(git rev-parse HEAD 2>/dev/null); then
  if [[ -z $(git status --porcelain --untracked-files=no 2>/dev/null) ]]; then dirty=clean; else dirty=dirty; fi
else commit=none; dirty=unknown; fi
red=0; summary=(); skipped=0; skipped_host=0; runstart=$(date +%s)
pinprofile=$profile; [[ $profile == heavy ]] && pinprofile=full   # heavy runs as written, like full
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
  [[ -n $module ]] || continue
  btimeout=""; bheap="-"; bsrc=""; bover=0
  if [[ $use_budgets == 1 ]]; then
    read -r btimeout bheap bsrc < <(budget_of "$module" "$variant"); bsrc="$bsrc ${btimeout}s/${bheap}"
    bover=$(over_hosted "$btimeout" "$bheap")
    if [[ $host == 1 && $bover == 0 ]]; then continue; fi   # host mode: only the rows a runner does not take
    if [[ $host != 1 && $bover == 1 ]]; then
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$commit" "$dirty" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$profile" "$module" "$variant" \
        "$expect" skipped "" "" "" SKIPPED-HOST "" >> "$results"
      echo "SKIPPED-HOST $module/$variant expect=$expect budget ${btimeout}s/${bheap} (over the hosted runner's ${hosted_t}s/${hosted_h}g: not run here, not a pass; tools/tla/run-host.sh $profile runs it on a build host)"
      skipped_host=$((skipped_host + 1)); continue
    fi
  fi
  if [[ $profile == small && $small == heavy ]]; then
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$commit" "$dirty" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$profile" "$module" "$variant" \
      "$expect" skipped "" "" "" SKIPPED-HEAVY "" >> "$results"
    echo "SKIPPED-HEAVY $module/$variant expect=$expect (over the small budget: not run, not a pass; profile heavy, full and sim run it)"
    skipped=$((skipped + 1)); continue
  fi
  consts=""; [[ $profile == small && $small != "-" ]] && consts=${small//,/ }
  start=$(date +%s)
  cap=${TLC_TIMEOUT:-}; capsrc="TLC_TIMEOUT env"
  if [[ $use_budgets == 1 && -z $cap ]]; then cap=$btimeout; capsrc="$bsrc"; fi
  javaopts=${TLC_JAVA_OPTS:-}; [[ $use_budgets == 1 && $bheap != "-" ]] && javaopts="-Xmx$bheap $javaopts"
  if [[ -n $cap ]] && command -v timeout >/dev/null; then
    TLC_JAVA_OPTS=$javaopts TLC_CONST=$consts TLA_OUT="$outroot/$module" timeout "$cap" bash "$here/check.sh" "$module" "$variant" "$root" > "$outroot/$module/run_${variant}.txt" 2>&1
  else
    TLC_JAVA_OPTS=$javaopts TLC_CONST=$consts TLA_OUT="$outroot/$module" bash "$here/check.sh" "$module" "$variant" "$root" > "$outroot/$module/run_${variant}.txt" 2>&1
  fi
  rc=$?
  secs=$(( $(date +%s) - start ))
  log="$outroot/$module/${module}_${variant}.log"
  violated=$(sed -nE 's/^Error: (Invariant|Temporal property|Action property) ([A-Za-z0-9_]+) (is|was) violated.*/\2/p' "$log" 2>/dev/null | head -1)
  case $rc in
    0) got=pass ;;
    12|13) got=fail ;;
    75) got=resource ;;   # check.sh's resource guard killed TLC (disk or memory): no verdict at all
    124) got="error(exit 124: exceeded the ${cap}s budget, $capsrc)" ;;   # coreutils timeout: the row's cap, never a verdict
    *) got="error(exit $rc)"
       grep -q 'java.lang.OutOfMemoryError' "$log" "$outroot/$module/run_${variant}.txt" 2>/dev/null && got="error(exit $rc: out of memory, java options '${javaopts:-none}')" ;;
  esac
  note=""
  if [[ $got == fail ]]; then
    if [[ ,$property, == *,"$violated",* && -n $violated ]]; then note=" violated=$violated"
    else got="fail(violated=${violated:-none}, expected one of ${property})"; fi
  fi
  if [[ $got == resource ]]; then
    verdict=RESOURCE; red=1
    note=" $(grep -m1 '^RESOURCE-GUARD' "$outroot/$module/run_${variant}.txt" 2>/dev/null || echo 'RESOURCE-GUARD: (line missing)')"
  elif [[ $got == error* ]]; then verdict=ERROR; red=1
  elif [[ $got == "$expect" ]]; then verdict=OK
  else verdict=RED; red=1; fi
  states=$(grep -oE '[0-9,]+ distinct states found' "$log" 2>/dev/null | tail -1)
  pin=$(awk -F'\t' -v m="$module" -v v="$variant" -v p="$pinprofile" '$1 == m && $2 == v && $3 == p { print $4; exit }' <<<"$pinrows")
  if [[ -n $pin && $got == pass ]]; then
    seen=$(tr -d ', ' <<<"${states%% distinct*}")
    if [[ $seen == "$pin" ]]; then note=" pinned=$pin"
    else note=" pinned=$pin found=${seen:-none}"; verdict=RED; red=1; fi
  fi
  simstat=""
  if [[ $profile == sim ]]; then
    simstat=$(grep -E '^Progress:.*traces generated' "$log" 2>/dev/null | tail -1 | sed -E 's/^Progress: ([0-9]+) states checked, ([0-9]+) traces generated \(trace length: mean=([0-9]+).*/traces=\2 mean-length=\3 states=\1/')
    [[ -n $simstat ]] || simstat="no trace count"
  fi
  tlcver=$(sed -nE 's/^TLC2 Version ([^ ]+).*/\1/p' "$log" 2>/dev/null | head -1)
  case $rc in 0) actual=pass ;; 12|13) actual=fail ;; *) actual=error ;; esac
  distinct=$(grep -oE '[0-9,]+ distinct states found' "$log" 2>/dev/null | tail -1 | tr -dc '0-9')
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$commit" "$dirty" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$profile" "$module" "$variant" \
    "$expect" "$actual" "$violated" "$distinct" "$tlcver" "$verdict" "$consts" >> "$results"
  line="$verdict $module/$variant expect=$expect got=$got${note} ${secs}s ${simstat:-${states:-no state count}} ${consts:+[$consts]}"
  echo "$line"; summary+=("$line")
  [[ $verdict != OK ]] && tail -20 "$outroot/$module/run_${variant}.txt" | sed 's/^/    /'
done <<<"$selected"

echo "run: evidence rows in $results (commit $commit, $dirty)"
echo "run: profile=$profile models=$filter variants=$vfilter configs=${#summary[@]} skipped-heavy=$skipped skipped-host=$skipped_host wall=$(( $(date +%s) - runstart ))s $([[ $red == 0 ]] && echo GREEN || echo RED)$([[ $profile == sim ]] && echo ' (simulation: sampled, not a proof)')"
exit $red
