#!/usr/bin/env bash
# Extract <Module> from the Java sources under <roots...>, then model-check it with TLC under config <Variant>.
# Usage: tools/tla/check.sh <Module> <Variant> <root>...   (env: TLA_JAR, TLC_WORKERS, TLC_OPTS, TLC_JAVA_OPTS, TLA_OUT)
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
( cd "$out" && exec java -XX:+UseParallelGC ${TLC_JAVA_OPTS:-} -cp "$jar" tlc2.TLC -workers "${TLC_WORKERS:-4}" -cleanup -deadlock ${TLC_OPTS:-} \
    -config "${module}_${variant}.cfg" "$module.tla" ) > "$out/${module}_${variant}.raw" 2>&1
rc=$?
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
