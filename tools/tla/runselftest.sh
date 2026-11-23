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
/// Done == <>(x = 3)
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
cat > "$work/Live.java.txt" <<'JAVA'
/// ```tlacfg Fix:live
/// PROPERTY Done
/// ```
class Live {}
JAVA
rows() { # rows <ok-expect> <ok-prop> <bad-expect> <bad-prop> <broken-expect> <broken-prop>
  printf 'Fix\tsrc\tok\t%s\t%s\t-\tx\nFix\tsrc\tbad\t%s\t%s\t-\tx\nFix\tsrc\tbroken\t%s\t%s\t-\tx\n' "$@"; }
fail=0
run() { # run <name> <want-exit: 0|nonzero> <pattern or -> <profile> <tsv rows via stdin>
  local name=$1 want=$2 pattern=$3 profile=$4 out rc
  cat > "$work/models.tsv"
  rm -f "$work/src/Broken.java" "$work/src/Live.java"; rm -rf "$work/out"   # a config extracted by an earlier case must not reach this one
  if grep -q broken "$work/models.tsv" || [[ ${KEEP_BROKEN:-} == 1 ]]; then cp "$work/Broken.java.txt" "$work/src/Broken.java"; fi
  if grep -qF $'\tlive\t' "$work/models.tsv"; then cp "$work/Live.java.txt" "$work/src/Live.java"; fi
  printf '%b' "${PINS:-}" > "$work/pins.tsv"
  out=$(cd "$work" && MODELS_TSV="$work/models.tsv" PINS_TSV="$work/pins.tsv" TLA_OUT="$work/out" TLC_WORKERS=1 SIM_SECONDS=3 SIM_TRACES=2000 bash "$here/run.sh" "$profile" 'Fix' "${VARIANTS:-.*}" 2>&1); rc=$?
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
# Liveness (#3593 survivors S6/S14): Fix/live names the temporal property Done, which stuttering violates (no fairness), so TLC
# exits 13, not 12; that exit must read as a violation, not as a TLC error.
live() { { rows pass - fail Small pass - | grep -v broken; printf 'Fix\tsrc\tlive\t%s\t%s\t-\tx\n' "$@"; }; }
run liveness-exit-13-satisfies-a-known-bad-row 0 '^OK Fix/live expect=fail got=fail' small < <(live fail Done)
# Pinned counts: Fix/ok reaches x = 0..3, four distinct states.
PINS='Fix\tok\tsmall\t4\tx\n' run pinned-count-holds 0 '^OK Fix/ok expect=pass got=pass pinned=4 ' small < <(rows pass - fail Small pass - | grep -v broken)
PINS='Fix\tok\tsmall\t5\tx\n' run pinned-count-differs-is-red nonzero '^RED Fix/ok expect=pass got=pass pinned=5 found=4' small < <(rows pass - fail Small pass - | grep -v broken)
PINS='Fix\tok\tfull\t5\tx\n' run pinned-count-other-profile-not-read 0 'GREEN' small < <(rows pass - fail Small pass - | grep -v broken)
PINS='Fix\tbad\tsmall\t4\tx\n' run pin-on-fail-row-is-error nonzero 'need a models.tsv pass row' small < <(rows pass - fail Small pass - | grep -v broken)
# Variant filter: only the selected rows run, and coverage still reads every row.
VARIANTS=ok run variant-filter-runs-only-the-selected 0 'configs=1 .*GREEN' small < <(rows pass - fail Small pass - | grep -v broken)
VARIANTS=ok KEEP_BROKEN=1 run variant-filter-keeps-coverage nonzero 'configs and models.tsv rows differ' small < <(rows pass - fail Small pass - | grep -v 'Fix.src.broken')
VARIANTS=nosuch run variant-filter-no-match-is-error nonzero 'no row matches variant' small < <(rows pass - fail Small pass - | grep -v broken)
res="$work/out/results.tsv"
cell() { awk -F'\t' -v m="$1" -v v="$2" -v c="$3" 'NR == 1 { for (i = 1; i <= NF; i++) if ($i == c) col = i } NR > 1 && $5 == m && $6 == v { print $col }' "$res"; }
want() { # want <name> <expected> <actual>
  if [[ $2 == "$3" ]]; then echo "PASS $1 ($3)"; else echo "FAIL $1: want '$2', got '$3'"; fail=1; fi; }
# Heavy rows: a row marked heavy (small column) is not run by small and never counts as a pass; heavy and full run it as written.
# Fix/bad is marked heavy and expects pass although it violates Small: it is RED exactly when it is run.
heavybad() { rows pass - pass - pass - | grep -v broken | awk -F'\t' -v OFS='\t' '$3 == "bad" { $6 = "heavy" } 1'; }
run heavy-row-is-skipped-by-name-at-small 0 '^SKIPPED-HEAVY Fix/bad expect=pass .*not a pass' small < <(heavybad)
want heavy-row-recorded-as-skipped 'skipped/SKIPPED-HEAVY' "$(cell Fix bad actual)/$(cell Fix bad verdict)"
want heavy-row-never-reached-tlc 0 "$(ls "$work"/out/Fix/Fix_bad.log 2>/dev/null | wc -l | tr -d ' ')"
want heavy-skip-leaves-the-other-row-run pass "$(cell Fix ok actual)"
run heavy-row-skipped-line-counts-in-summary 0 'configs=1 skipped-heavy=1 ' small < <(heavybad)
run heavy-profile-runs-the-heavy-row-and-it-is-red nonzero '^RED Fix/bad expect=pass got=fail' heavy < <(heavybad)
run heavy-profile-runs-only-heavy-rows nonzero 'configs=1 skipped-heavy=0 .*RED' heavy < <(heavybad)
run full-profile-runs-heavy-rows-as-written nonzero '^RED Fix/bad expect=pass got=fail' full < <(heavybad)
# the heavy profile reads the pins of profile full (it runs as written, like full); Fix/ok is marked heavy and reaches four states
heavyok() { rows pass - fail Small pass - | grep -v broken | awk -F'\t' -v OFS='\t' '$3 == "ok" { $6 = "heavy" } 1'; }
PINS='Fix\tok\tfull\t4\tx\n' run heavy-profile-reads-the-full-pin-and-it-holds 0 '^OK Fix/ok expect=pass got=pass pinned=4 ' heavy < <(heavyok)
PINS='Fix\tok\tfull\t5\tx\n' run heavy-profile-reads-the-full-pin-and-it-differs-is-red nonzero '^RED Fix/ok expect=pass got=pass pinned=5 found=4' heavy < <(heavyok)
# an unknown profile is refused before anything runs (exit 2), so a typo cannot read as an empty green run
run unknown-profile-is-an-error nonzero 'profile must be small, full, sim or heavy' bogus < <(rows pass - fail Small pass - | grep -v broken)
run heavy-profile-without-a-heavy-row-is-an-error nonzero 'no row marked heavy' heavy < <(rows pass - fail Small pass - | grep -v broken)
# Evidence rows (#3584): one row per config that ran, with the actual verdict read from TLC's exit code, never from the expectation.
run rows-control 0 'evidence rows in' small < <(rows pass - fail Small pass - | grep -v broken)
want rows-count 3 "$(wc -l < "$res" | tr -d ' ')"
want rows-ok-actual pass "$(cell Fix ok actual)"
want rows-ok-distinct 4 "$(cell Fix ok distinct)"
want rows-ok-verdict OK "$(cell Fix ok verdict)"
want rows-bad-actual fail "$(cell Fix bad actual)"
want rows-bad-violated Small "$(cell Fix bad violated)"
want rows-bad-verdict-ok-because-expected-fail OK "$(cell Fix bad verdict)"
want rows-outside-git-commit-none none "$(cell Fix ok commit)"
want rows-tlc-version-read 1 "$([[ -n $(cell Fix ok tlc) ]] && echo 1 || echo 0)"
run rows-wrong-verdict nonzero - small < <(rows fail Big fail Small pass - | grep -v broken)
want rows-wrong-verdict-red RED "$(cell Fix ok verdict)"
want rows-wrong-verdict-keeps-expect-and-actual 'fail/pass' "$(cell Fix ok expect)/$(cell Fix ok actual)"
run rows-error nonzero - small < <(rows pass - fail Small pass -)
want rows-error-actual error "$(cell Fix broken actual)"
want rows-error-verdict ERROR "$(cell Fix broken verdict)"
want rows-error-never-distinct-from-a-pass "" "$(cell Fix broken distinct)"
# exit 13 (a violated temporal property) is a violation in the evidence row too (#3593 survivor S6), not an error
run rows-liveness-control 0 - small < <(live fail Done)
want rows-liveness-actual fail "$(cell Fix live actual)"
want rows-liveness-verdict OK "$(cell Fix live verdict)"
# a results file with another header is refused, never appended to
printf 'commit\tother\n' > "$work/foreign.tsv"
out=$(cd "$work" && MODELS_TSV="$work/models.tsv" PINS_TSV="$work/pins.tsv" TLA_OUT="$work/out2" TLA_RESULTS="$work/foreign.tsv" bash "$here/run.sh" small Fix 2>&1); rc=$?
want rows-foreign-header-refused 2 "$rc"
want rows-foreign-header-untouched 1 "$(wc -l < "$work/foreign.tsv" | tr -d ' ')"
# inside a git repository the row names HEAD, and a tracked change makes the row dirty
rows pass - fail Small pass - | grep -v broken > "$work/models.tsv"; : > "$work/pins.tsv"
rm -rf "$work/out"; git -C "$work" init -q && git -C "$work" add -A >/dev/null && git -C "$work" -c user.name=t -c user.email=t@t -c commit.gpgsign=false commit -q -m fixture
rm -rf "$work/out"; (cd "$work" && MODELS_TSV="$work/models.tsv" PINS_TSV="$work/pins.tsv" TLA_OUT="$work/out" TLC_WORKERS=1 bash "$here/run.sh" small Fix >/dev/null 2>&1)
want rows-git-commit-is-head "$(git -C "$work" rev-parse HEAD)" "$(cell Fix ok commit)"
want rows-git-clean clean "$(cell Fix ok dirty)"
echo changed >> "$work/src/Fix.java"
rm -rf "$work/out"; (cd "$work" && MODELS_TSV="$work/models.tsv" PINS_TSV="$work/pins.tsv" TLA_OUT="$work/out" TLC_WORKERS=1 bash "$here/run.sh" small Fix >/dev/null 2>&1)
want rows-git-dirty dirty "$(cell Fix ok dirty)"
exit $fail
