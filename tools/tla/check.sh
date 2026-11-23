#!/usr/bin/env bash
# Extract <Module> from the Java sources under <roots...>, then model-check it with TLC under config <Variant>.
# Usage: tools/tla/check.sh <Module> <Variant> <root>...   (env: TLA_JAR, TLC_WORKERS, TLC_OPTS, TLC_JAVA_OPTS, TLA_OUT,
#   TLA_DISK_MAX, TLA_MEM_MIN_MB, TLA_GUARD_SECS)
# Resource guard: TLC runs in the background and is watched every TLA_GUARD_SECS (default 10) seconds. It is killed (TERM, KILL
# after 10 s; only the PID started here) when the filesystem holding the output dir is >= TLA_DISK_MAX percent full (default 90)
# or MemAvailable is below TLA_MEM_MIN_MB (default 2048; read from /proc/meminfo, not checked where there is none). The line
# `RESOURCE-GUARD: killed TLC: <disk N% | MemAvailable M MB>` goes to the log and stdout, this run's states dir is removed and
# the exit code is 75: a guard kill is never a model verdict (run.sh reads 75 as RESOURCE). s55: TLC states/ dirs filled two
# hosts (ENOSPC) and the OOM killer took a run (exit 137).
# Terminal states are normal (bounded acquires), so the deadlock check is off.
# Every "line N ... of module <Module>" position in TLC's output is annotated with its Java source position.
set -euo pipefail
module=$1; variant=$2; shift 2
here=$(cd "$(dirname "$0")" && pwd)
jar=${TLA_JAR:-$HOME/tools/tla/tla2tools.jar}
out=${TLA_OUT:-target/tla/$module}
python3 "$here/extract.py" --module "$module" --out "$out" --base . "$@"
cfg="$out/${module}_${variant}.cfg"
[ -n "${TLC_CONST:-}" ] && echo "check: constants overridden: $TLC_CONST"
[ -f "$cfg" ] || { echo "check: ERROR no config fragment $module:$variant" >&2; exit 2; }
# TLC_CONST="Name=value ..." overrides a constant of the config for this run (bounds scaling); recorded in the log.
for kv in ${TLC_CONST:-}; do
  grep -qE "^(CONSTANTS?)?[[:space:]]*${kv%%=*}[[:space:]]*=" "$cfg" || { echo "check: ERROR no constant ${kv%%=*} in $cfg" >&2; exit 2; }
  sed -i.bak -E "s/^((CONSTANTS?)?[[:space:]]*${kv%%=*}[[:space:]]*=[[:space:]]*).*/\1${kv#*=}/" "$cfg"
done
set +e
disk_max=${TLA_DISK_MAX:-90}; mem_min=${TLA_MEM_MIN_MB:-2048}; guard_secs=${TLA_GUARD_SECS:-10}
for v in "$disk_max" "$mem_min" "$guard_secs"; do [[ $v =~ ^[0-9]+$ ]] || { echo "check: ERROR TLA_DISK_MAX, TLA_MEM_MIN_MB and TLA_GUARD_SECS must be whole numbers, got '$v'" >&2; exit 2; }; done
[[ $guard_secs -ge 1 ]] || guard_secs=1
raw="$out/${module}_${variant}.raw"
metadir="states/run$$"   # this run's own states dir (relative to $out): the only dir a guard kill removes
( cd "$out" && exec java -XX:+UseParallelGC ${TLC_JAVA_OPTS:-} -cp "$jar" tlc2.TLC -workers "${TLC_WORKERS:-4}" -cleanup -deadlock ${TLC_OPTS:-} \
    -metadir "$metadir" -config "${module}_${variant}.cfg" "$module.tla" ) > "$raw" 2>&1 &
tlc=$!
# a TERM/INT to this script (run.sh's timeout) takes the TLC it started down with it, never a pattern match
trap 'kill -TERM "$tlc" 2>/dev/null; sleep 1; kill -KILL "$tlc" 2>/dev/null; rm -rf "$out/$metadir"; rmdir "$out/states" 2>/dev/null; exit 143' TERM INT
guard=""; tick=0
while kill -0 "$tlc" 2>/dev/null; do
  if (( tick % guard_secs == 0 )); then
    pct=$(df -P "$out" | awk 'NR == 2 { gsub("%", "", $5); print $5 }')
    avail=""; [[ -r /proc/meminfo ]] && avail=$(awk '/^MemAvailable:/ { print int($2 / 1024) }' /proc/meminfo)
    if [[ $pct =~ ^[0-9]+$ ]] && (( pct >= disk_max )); then guard="disk ${pct}%"
    elif [[ $avail =~ ^[0-9]+$ ]] && (( avail < mem_min )); then guard="MemAvailable ${avail} MB"; fi
    if [[ -n $guard ]]; then
      kill -TERM "$tlc" 2>/dev/null
      for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$tlc" 2>/dev/null || break; sleep 1; done
      kill -KILL "$tlc" 2>/dev/null
      break
    fi
  fi
  sleep 1; tick=$((tick + 1))
done
wait "$tlc" 2>/dev/null; rc=$?
trap - TERM INT
rm -rf "$out/$metadir"; rmdir "$out/states" 2>/dev/null   # the parent goes only when empty: another run's dir keeps it
if [[ -n $guard ]]; then
  echo "RESOURCE-GUARD: killed TLC: $guard" | tee -a "$raw"
  cp "$raw" "$out/${module}_${variant}.log"
  echo "check: full log $out/${module}_${variant}.log"
  echo "check: TLC killed by the resource guard ($module/$variant), exit 75"
  exit 75
fi
awk -v map="$out/$module.map.tsv" -v mod="$module" '
  BEGIN { while ((getline l < map) > 0) { split(l, f, "\t"); src[f[1]] = f[2] } }
  { line = $0
    if (match(line, "line [0-9]+, col [0-9]+ to line [0-9]+, col [0-9]+ of module " mod)) {
      split(substr(line, RSTART, RLENGTH), w, /[ ,]+/); line = line "   [java " src[w[2]] "]" }
    print line }' "$out/${module}_${variant}.raw" > "$out/${module}_${variant}.log"
# Summary: the verdict, the counterexample as a sequence of actions, and the state counts. Full log beside it.
grep -E '^Error:|is violated|^State [0-9]+:|^Temporal|^Back to state|states generated|distinct states|^Model checking completed|^Finished in|depth of the complete|Semantic error|Parse Error|^\*\*\*|line [0-9]+, col' \
    "$out/${module}_${variant}.log" | sed -E 's/ line [0-9]+, col [0-9]+ to line [0-9]+, col [0-9]+ of module [A-Za-z_]+>/>/' | head -${TLC_SUMMARY_LINES:-60}
echo "check: full log $out/${module}_${variant}.log"
echo "check: TLC exit $rc ($module/$variant)"
exit "$rc"
