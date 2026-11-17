#!/usr/bin/env bash
# Self-test of run.sh's verdict logic on a tiny fixture model (module Fix, three configs): every deliberately wrong
# models.tsv must make run.sh exit non-zero and name the cause, and the correct one must exit 0. A run.sh that always
# reports OK, or that reads a TLC error as a violation, fails here. Usage: tools/tla/runselftest.sh   (env: TLA_JAR)
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
mkdir -p "$work/src"
cat > "$work/src/Fix.java" <<'JAVA'
/// ```tla Fix:head
/// EXTENDS Naturals
/// VARIABLE x
/// Init == x = 0
/// ```
///
/// ```tla Fix:Inc
/// Inc == x < 3 /\ x' = x + 1
/// ```
///
/// ```tla Fix:tail
/// Next == Inc
/// Spec == Init /\ [][Next]_x
/// Small == x <= 2
/// Big == x <= 5
/// ```
///
/// ```tlacfg Fix:common
/// SPECIFICATION Spec
/// ```
///
/// ```tlacfg Fix:ok
/// INVARIANT Big
/// ```
///
/// ```tlacfg Fix:bad
/// INVARIANT Small
/// ```
///
class Fix {}
JAVA
cat > "$work/Broken.java.txt" <<'JAVA'
/// ```tlacfg Fix:broken
/// INVARIANT Missing
/// ```
class Broken {}
JAVA
rows() { # rows <ok-expect> <ok-prop> <bad-expect> <bad-prop> <broken-expect> <broken-prop>
  printf 'Fix\tsrc\tok\t%s\t%s\t-\tx\nFix\tsrc\tbad\t%s\t%s\t-\tx\nFix\tsrc\tbroken\t%s\t%s\t-\tx\n' "$@"; }
fail=0
run() { # run <name> <want-exit: 0|nonzero> <pattern or -> <profile> <tsv rows via stdin>
  local name=$1 want=$2 pattern=$3 profile=$4 out rc
  cat > "$work/models.tsv"
  rm -f "$work/src/Broken.java"
  if grep -q broken "$work/models.tsv" || [[ ${KEEP_BROKEN:-} == 1 ]]; then cp "$work/Broken.java.txt" "$work/src/Broken.java"; fi
  out=$(cd "$work" && MODELS_TSV="$work/models.tsv" TLA_OUT="$work/out" TLC_WORKERS=1 SIM_SECONDS=3 SIM_TRACES=2000 bash "$here/run.sh" "$profile" 'Fix' 2>&1); rc=$?
  if { [[ $want == 0 && $rc == 0 ]] || [[ $want == nonzero && $rc != 0 ]]; } && { [[ $pattern == - ]] || grep -qE "$pattern" <<<"$out"; }; then
    echo "PASS $name (exit $rc)"
  else echo "FAIL $name: want exit $want, got $rc, pattern '$pattern'"; sed 's/^/    /' <<<"$out" | tail -25; fail=1; fi; }
# Fixture: ok holds, bad violates Small, broken names an undefined invariant (a TLC error, never a violation).
run control 0 'run: profile=small .* GREEN' small < <(rows pass - fail Small pass - | grep -v broken)
run control-sim 0 'sim .* NOT a proof|sampled, not a proof' sim < <(rows pass - fail Small pass - | grep -v broken)
run wrong-verdict-pass-row-marked-fail nonzero '^RED Fix/ok expect=fail got=pass' small < <(rows fail Big fail Small pass - | grep -v broken)
run wrong-verdict-known-bad-passes nonzero '^RED Fix/bad expect=pass got=fail' small < <(rows pass - pass - pass - | grep -v broken)
run wrong-property-named nonzero '^RED Fix/bad .*violated=Small' small < <(rows pass - fail Big pass - | grep -v broken)
run tlc-error-reads-ERROR nonzero '^ERROR Fix/broken expect=pass got=error' small < <(rows pass - fail Small pass -)
run tlc-error-never-satisfies-fail-row nonzero '^ERROR Fix/broken expect=fail got=error' small < <(rows pass - fail Small fail Missing)
KEEP_BROKEN=1 run missing-row-is-red nonzero 'configs and models.tsv rows differ' small < <(rows pass - fail Small pass - | grep -v 'Fix.src.broken')
run fail-row-without-property nonzero 'need expect pass.fail' small < <(rows pass - fail - pass - | grep -v broken)
run sim-known-bad-must-still-fail nonzero '^RED Fix/bad expect=pass got=fail' sim < <(rows pass - pass - pass - | grep -v broken)
exit $fail
