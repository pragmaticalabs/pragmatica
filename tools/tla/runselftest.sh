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
  [[ -n ${BUDGETS_TSV:-} ]] || { BUDGETS_TSV="$work/b_default.tsv"; printf '*\t*\t18000\t-\tdefault\n@hosted\tlimit\t18000\t12g\tlimit\n' > "$BUDGETS_TSV"; }   # the fixture models.tsv has none of the real rows
  out=$(cd "$work" && BUDGETS_TSV="$BUDGETS_TSV" MODELS_TSV="$work/models.tsv" PINS_TSV="$work/pins.tsv" TLA_OUT="$work/out" TLC_WORKERS=1 SIM_SECONDS=3 SIM_TRACES=2000 bash "$here/run.sh" "$profile" 'Fix' "${VARIANTS:-.*}" 2>&1); rc=$?
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
# Resource guard (check.sh exit 75): a disk threshold of 0 percent is always crossed, so TLC is killed at the first check; that
# reads RESOURCE (red, actual=error), never a pass or a fail, with the guard line as the note, and leaves no states dir behind.
TLA_DISK_MAX=0 run guard-kill-reads-RESOURCE nonzero '^RESOURCE Fix/ok expect=pass got=resource RESOURCE-GUARD: killed TLC: disk [0-9]+%' small < <(rows pass - fail Small pass - | grep -v broken)
want guard-kill-actual-error error "$(cell Fix ok actual)"
want guard-kill-verdict RESOURCE "$(cell Fix ok verdict)"
want guard-kill-leaves-no-states-dir 0 "$(find "$work/out" -type d -name 'run[0-9]*' | wc -l | tr -d ' ')"
TLA_DISK_MAX=0 run guard-kill-never-satisfies-a-fail-row nonzero '^RESOURCE Fix/bad expect=fail got=resource' small < <(rows pass - fail Small pass - | grep -v broken)
TLA_DISK_MAX=abc run guard-knob-not-a-number-is-an-error nonzero 'must be whole numbers' small < <(rows pass - fail Small pass - | grep -v broken)
# check.sh itself, with a stub `java` that ignores TERM, creates its -metadir with a file in it and sleeps: the kill must
# escalate to KILL, take only that PID, remove the run's states dir and exit 75; a TERM to check.sh must take TLC down too.
mkdir -p "$work/stub"; cat > "$work/stub/java" <<'STUB'
#!/usr/bin/env bash
echo $$ > "$STUB_PID"
while [[ $# -gt 0 ]]; do [[ $1 == -metadir ]] && { mkdir -p "$2" && echo x > "$2/f"; }; shift; done
trap '' TERM
while :; do sleep 1; done
STUB
chmod +x "$work/stub/java"
stubcheck() { # stubcheck <out> <env assignments...>: run check.sh on the fixture with the stub java, under a 60 s cap
  local o=$1; shift
  rm -rf "$work/sc"; mkdir -p "$work/sc"
  ( cd "$work" && env PATH="$work/stub:$PATH" STUB_PID="$work/stub.pid" TLA_OUT="$work/sc" TLA_GUARD_SECS=1 "$@" timeout 60 bash "$here/check.sh" Fix ok src > "$o" 2>&1 ); }
alive() { kill -0 "$(cat "$work/stub.pid")" 2>/dev/null && echo alive || echo dead; }
t0=$(date +%s); stubcheck "$work/sc.out" TLA_DISK_MAX=0; rc=$?; secs=$(( $(date +%s) - t0 ))
want guard-kill-exit-code-75 75 "$rc"
want guard-kill-escalates-to-KILL-after-10s dead "$(alive)"
want guard-kill-prints-the-line 1 "$(grep -cE '^RESOURCE-GUARD: killed TLC: disk [0-9]+%$' "$work/sc.out")"
want guard-kill-line-is-in-the-log 1 "$(grep -c '^RESOURCE-GUARD' "$work/sc/Fix_ok.log")"
want guard-kill-removes-the-states-dir 0 "$(find "$work/sc" -path '*states*' | wc -l | tr -d ' ')"
want guard-kill-takes-about-ten-seconds-not-the-whole-cap 1 "$([[ $secs -lt 40 ]] && echo 1 || echo 0)"
# the boundary is >= : a limit equal to the current usage kills
pct=$(df -P "$work" | awk 'NR == 2 { gsub("%", "", $5); print $5 }')
stubcheck "$work/sc.out" TLA_DISK_MAX="$pct"; want guard-disk-limit-equal-to-usage-kills 75 "$?"
[[ -r /proc/meminfo ]] && { stubcheck "$work/sc.out" TLA_MEM_MIN_MB=99999999; want guard-memory-floor-above-available-kills 75 "$?"
  want guard-memory-line-names-MemAvailable 1 "$(grep -cE '^RESOURCE-GUARD: killed TLC: MemAvailable [0-9]+ MB$' "$work/sc.out")"; }
# a TERM to check.sh (run.sh's timeout) takes the TLC it started down with it and removes the states dir
rm -rf "$work/sc"; mkdir -p "$work/sc"
( cd "$work" && exec env PATH="$work/stub:$PATH" STUB_PID="$work/stub.pid" TLA_OUT="$work/sc" TLA_DISK_MAX=101 TLA_MEM_MIN_MB=0 bash "$here/check.sh" Fix ok src > "$work/sc.out" 2>&1 ) & cpid=$!
sleep 5; kill -TERM "$cpid"; wait "$cpid"; want term-to-check-exits-143 143 "$?"
want term-to-check-kills-its-tlc dead "$(alive)"
want term-to-check-removes-the-states-dir 0 "$(find "$work/sc" -path '*states*' | wc -l | tr -d ' ')"
# Per-row budgets (#3636): the full and heavy profiles read budgets.tsv per row. BUDGETS_TSV points run.sh at a fixture table.
base_budgets() { printf '*\t*\t18000\t-\tdefault\n@hosted\tlimit\t18000\t12g\tlimit\n'; }
fixrows() { rows pass - fail Small pass - | grep -v broken; }
{ base_budgets; printf 'Fix\tok\t21600\t8g\tover the hosted time\n'; } > "$work/b_over.tsv"
BUDGETS_TSV="$work/b_over.tsv" run budget-over-hosted-limit-is-named-and-not-run 0 '^SKIPPED-HOST Fix/ok expect=pass budget 21600s/8g .*not a pass' full < <(fixrows)
want budget-skipped-host-recorded 'skipped/SKIPPED-HOST' "$(cell Fix ok actual)/$(cell Fix ok verdict)"
want budget-skipped-host-never-reached-tlc 0 "$(ls "$work"/out/Fix/Fix_ok.log 2>/dev/null | wc -l | tr -d ' ')"
want budget-skip-leaves-the-other-row-run OK "$(cell Fix bad verdict)"
BUDGETS_TSV="$work/b_over.tsv" run budget-skipped-host-counts-in-summary 0 'configs=1 skipped-heavy=0 skipped-host=1 ' full < <(fixrows)
BUDGETS_TSV="$work/b_over.tsv" TLA_HOST=1 run budget-host-mode-runs-only-the-over-limit-rows 0 'configs=1 skipped-heavy=0 skipped-host=0 .*GREEN' full < <(fixrows)
want budget-host-mode-ran-it OK "$(cell Fix ok verdict)"
want budget-host-mode-did-not-run-the-fitting-row 0 "$(ls "$work"/out/Fix/Fix_bad.log 2>/dev/null | wc -l | tr -d ' ')"
BUDGETS_TSV="$work/b_over.tsv" TLA_HOST=list run budget-list-prints-the-over-limit-rows 0 '^Fix/ok'$'\t''21600'$'\t''8g$' full < <(fixrows)
{ base_budgets; printf 'Fix\tbad\t600\t20g\tover the hosted heap\n'; } > "$work/b_heap.tsv"
BUDGETS_TSV="$work/b_heap.tsv" run budget-heap-alone-is-over-the-limit 0 '^SKIPPED-HOST Fix/bad expect=fail budget 600s/20g' full < <(fixrows)
{ base_budgets; printf 'Fix\tok\t18000\t12g\tat the limit\n'; } > "$work/b_at.tsv"
BUDGETS_TSV="$work/b_at.tsv" run budget-exactly-at-the-limit-runs-on-the-runner 0 'skipped-host=0 .*GREEN' full < <(fixrows)
{ base_budgets; printf 'Fix\tnosuch\t10\t-\tx\n'; } > "$work/b_bad.tsv"
BUDGETS_TSV="$work/b_bad.tsv" run budget-table-naming-a-missing-row-is-refused nonzero 'Fix/nosuch: no such row in models.tsv' full < <(fixrows)
# the cap: a stub java that ignores TERM outlives a 1 s budget; the row reads ERROR naming the cap and its source, never a verdict.
# The stub gives up after 20 s (stub3), so a run.sh that ignores the cap fails these cases instead of hanging the self-test.
mkdir -p "$work/stub3"; sed 's/^while :; do sleep 1; done$/for _ in $(seq 20); do sleep 1; done/' "$work/stub/java" > "$work/stub3/java"; chmod +x "$work/stub3/java"
{ base_budgets; printf 'Fix\tok\t1\t-\ttiny\n'; } > "$work/b_tiny.tsv"
VARIANTS=ok PATH="$work/stub3:$PATH" STUB_PID="$work/stub.pid" BUDGETS_TSV="$work/b_tiny.tsv" run budget-timeout-reads-ERROR-naming-the-budget nonzero '^ERROR Fix/ok expect=pass got=error\(exit 124: exceeded the 1s budget, budgets.tsv row 1s/-\)' full < <(fixrows)
want budget-timeout-actual-error error "$(cell Fix ok actual)"
want budget-timeout-verdict-ERROR ERROR "$(cell Fix ok verdict)"
want budget-timeout-took-its-tlc-down dead "$(alive)"
printf '*\t*\t1\t-\ttiny default\n@hosted\tlimit\t18000\t12g\tlimit\n' > "$work/b_tinydef.tsv"
VARIANTS=ok PATH="$work/stub3:$PATH" STUB_PID="$work/stub.pid" BUDGETS_TSV="$work/b_tinydef.tsv" run budget-default-row-applies-to-a-row-without-its-own nonzero 'exceeded the 1s budget, default row 1s/-' full < <(fixrows)
VARIANTS=ok PATH="$work/stub3:$PATH" STUB_PID="$work/stub.pid" TLC_TIMEOUT=2 BUDGETS_TSV="$work/b_over.tsv" run budget-env-timeout-overrides-the-row nonzero 'exceeded the 2s budget, TLC_TIMEOUT env' full < <(fixrows)
# the heap: a stub java that records its arguments shows the row's -Xmx in front of the env's java options
mkdir -p "$work/stub2"; printf '#!/usr/bin/env bash\necho "$@" > "$STUB_ARGS"\nexit 0\n' > "$work/stub2/java"; chmod +x "$work/stub2/java"
{ base_budgets; printf 'Fix\tok\t100\t6g\tsix\n'; } > "$work/b_six.tsv"
VARIANTS=ok PATH="$work/stub2:$PATH" STUB_ARGS="$work/args.txt" BUDGETS_TSV="$work/b_six.tsv" run budget-heap-reaches-java 0 - full < <(fixrows)
want budget-heap-is-xmx-6g 1 "$(grep -c -- '-Xmx6g' "$work/args.txt")"
VARIANTS=ok PATH="$work/stub2:$PATH" STUB_ARGS="$work/args.txt" TLC_JAVA_OPTS=-Xmx1g BUDGETS_TSV="$work/b_six.tsv" run budget-env-java-options-follow-the-row-heap 0 - full < <(fixrows)
want budget-env-xmx-comes-after-the-row-xmx 1 "$(grep -cE -- '-Xmx6g.* -Xmx1g' "$work/args.txt")"
VARIANTS=ok PATH="$work/stub2:$PATH" STUB_ARGS="$work/args.txt" BUDGETS_TSV="$work/b_six.tsv" run budget-small-profile-ignores-the-table 0 - small < <(fixrows)
want budget-small-has-no-row-heap 0 "$(grep -c -- '-Xmx' "$work/args.txt")"
# run-host.sh: the SKIPPED-HOST rows run one by one under their own budget, behind a memory precheck that never starts a row without room
hostrun() { # hostrun <name> <want-exit: 0|nonzero> <pattern> <profile> [env assignments...]: Fix/ok is over the limit in b_over.tsv
  local name=$1 want=$2 pattern=$3 profile=$4 out rc; shift 4
  fixrows > "$work/models.tsv"; rm -rf "$work/out"; : > "$work/pins.tsv"
  out=$(cd "$work" && env "$@" MODELS_TSV="$work/models.tsv" PINS_TSV="$work/pins.tsv" TLA_OUT="$work/out" TLC_WORKERS=1 BUDGETS_TSV="$work/b_over.tsv" bash "$here/run-host.sh" "$profile" 2>&1); rc=$?
  if { [[ $want == 0 && $rc == 0 ]] || [[ $want == nonzero && $rc != 0 ]]; } && grep -qE "$pattern" <<<"$out"; then echo "PASS $name (exit $rc)"
  else echo "FAIL $name: want exit $want, got $rc, pattern '$pattern'"; sed 's/^/    /' <<<"$out" | tail -25; fail=1; fi; }
printf 'MemAvailable:   %s kB\n' 99999999 > "$work/mem_big"; printf 'MemAvailable:   %s kB\n' 1024 > "$work/mem_small"
hostrun host-script-runs-the-over-limit-row-only 0 'rows=1 green=1 red=0 not-started=0 .*GREEN' full TLA_MEMINFO="$work/mem_big"
want host-script-row-recorded-OK-at-profile-full 'OK/full' "$(cell Fix ok verdict)/$(cell Fix ok profile)"
want host-script-did-not-run-the-fitting-row 0 "$(ls "$work"/out/Fix/Fix_bad.log 2>/dev/null | wc -l | tr -d ' ')"
hostrun host-script-never-starts-a-row-without-memory nonzero '^NOT-STARTED Fix/ok budget 21600s/8g: MemAvailable 1 MB < 10240 MB' full TLA_MEMINFO="$work/mem_small" TLA_HOST_WAIT_SECS=2 TLA_HOST_POLL_SECS=1
want host-script-not-started-row-has-no-result 0 "$(ls "$work"/out/Fix/Fix_ok.log 2>/dev/null | wc -l | tr -d ' ')"
hostrun host-script-heavy-without-heavy-rows-names-nothing-to-run nonzero 'no row marked heavy' heavy TLA_MEMINFO="$work/mem_big"
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
