#!/usr/bin/env bash
# Run every model config listed in models.tsv and compare each verdict with the expected one.
# Usage: tools/tla/run.sh [small|full] [module-regex]     (run from the repository root)
#   small: the config's constants with the row's overrides; full: every config as written.
# A "pass" row must finish with no violation (TLC exit 0). A "fail" row is a known-bad config (an earlier version of
# the code, or a filed defect) and must end in a violation (TLC exit 12 safety, 13 liveness); if it passes, the model
# no longer reaches the code it pins, and the run is red. Any other TLC exit (parse, config, system error, timeout)
# is red. Every config the extractor finds must have a row, so no config is skipped silently.
# Env: TLA_JAR, TLC_WORKERS, TLC_OPTS (passed to check.sh), TLA_OUT (default target/tla), TLC_TIMEOUT (seconds per
# config, needs coreutils timeout).
set -uo pipefail
profile=${1:-small}; filter=${2:-.*}
here=$(cd "$(dirname "$0")" && pwd)
outroot=${TLA_OUT:-target/tla}
[[ $profile == small || $profile == full ]] || { echo "run: ERROR profile must be small or full, got '$profile'" >&2; exit 2; }
rows=$(grep -vE '^[[:space:]]*(#|$)' "$here/models.tsv" | awk -F'\t' -v f="$filter" '$1 ~ "^(" f ")$"')
[[ -n $rows ]] || { echo "run: ERROR no model matches '$filter'" >&2; exit 2; }
red=0; summary=()

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

while IFS=$'\t' read -r module root variant expect small _; do
  consts=""; [[ $profile == small && $small != "-" ]] && consts=${small//,/ }
  start=$(date +%s)
  if [[ -n ${TLC_TIMEOUT:-} ]] && command -v timeout >/dev/null; then
    TLC_CONST=$consts TLA_OUT="$outroot/$module" timeout "$TLC_TIMEOUT" bash "$here/check.sh" "$module" "$variant" "$root" > "$outroot/$module/run_${variant}.txt" 2>&1
  else
    TLC_CONST=$consts TLA_OUT="$outroot/$module" bash "$here/check.sh" "$module" "$variant" "$root" > "$outroot/$module/run_${variant}.txt" 2>&1
  fi
  rc=$?
  secs=$(( $(date +%s) - start ))
  case $rc in
    0) got=pass ;;
    12|13) got=fail ;;
    *) got="error(exit $rc)" ;;
  esac
  states=$(grep -oE '[0-9,]+ distinct states found' "$outroot/$module/${module}_${variant}.log" 2>/dev/null | tail -1)
  if [[ $got == "$expect" ]]; then verdict=OK; else verdict=RED; red=1; fi
  line="$verdict $module/$variant expect=$expect got=$got ${secs}s ${states:-no state count} ${consts:+[$consts]}"
  echo "$line"; summary+=("$line")
  [[ $verdict == RED ]] && tail -20 "$outroot/$module/run_${variant}.txt" | sed 's/^/    /'
done <<<"$rows"

echo "run: profile=$profile models=$filter configs=${#summary[@]} $([[ $red == 0 ]] && echo GREEN || echo RED)"
exit $red
