#!/usr/bin/env bash
#
# forge.sh — the local forge gate (#556).
#
# Forge tests are the ONLY gate that runs a real multi-node Aether cluster: in-JVM, 3-7 nodes,
# real consensus, real streams, real deployment FSM. Before this script nothing a developer ran
# locally executed them — `./build.sh` compiles them and says so in its own banner — so the
# practical loop was "green build, green unit suites, push, find out 30 minutes later in CI".
#
# The failure mode forge catches is the one unit tests structurally cannot: a change that
# compiles, lints, passes thousands of unit tests, and then HANGS OR LIVELOCKS a real cluster.
# That has happened: a deployment-FSM + KV-codec change passed build.sh and 2915 unit tests with
# zero failures, then wedged forge-tests for 30 minutes with zero failing assertions.
#
# Cost asymmetry is the whole argument: the smoke set below costs a couple of minutes locally;
# finding the same defect in CI costs 30 minutes, a red release branch, and the diagnosis.
#
# Usage:
#   ./forge.sh                    smoke  — formation + deployment + one stream path (default)
#   ./forge.sh ci                 exactly what CI runs (everything except @Tag("Heavy"))
#   ./forge.sh full               every forge test, Heavy probes included (slow)
#   ./forge.sh <ClassName>        a single class, e.g. ./forge.sh ClusterFormationTest
#
# Run ./build.sh first (or at least `mvn install -DskipTests`) — this script does not rebuild
# the runtime, it only runs the gate.
set -uo pipefail

MODE="${1:-smoke}"
MODULE="aether/forge/forge-tests"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

# `verify`, not `integration-test`. Failsafe only ENFORCES failures at `verify`; with
# `integration-test` the build prints BUILD SUCCESS while tests fail, which has already produced
# one nearly-reported false green. The module scope is hard-coded and deliberately NOT
# configurable: it is what keeps HetznerCloudIT (a different module, which provisions a real paid
# server when HCLOUD_TOKEN is set) out of the reactor. Do not add a module override to this script.
BASE_ARGS=(verify -Pwith-e2e -pl "$MODULE")

case "$MODE" in
    smoke)
        SELECT=(-Dgroups=Smoke)
        DESC="SMOKE — formation + deployment/invocation + one stream path"
        ;;
    ci)
        SELECT=(-Dfailsafe.excludedGroups=Heavy)
        DESC="CI-EQUIVALENT — everything except @Tag(\"Heavy\")"
        ;;
    full)
        SELECT=()
        DESC="FULL — every forge test including Heavy probes"
        ;;
    -h|--help|help)
        sed -n '3,28p' "$0" | sed 's/^# \{0,1\}//'
        exit 0
        ;;
    *)
        SELECT=(-Dit.test="$MODE" -DfailIfNoSpecifiedTests=false -Dfailsafe.excludedGroups=)
        DESC="SINGLE CLASS — $MODE"
        ;;
esac

echo "=============================================================="
echo " Forge gate: $DESC"
echo "=============================================================="

REPORTS="$MODULE/target/failsafe-reports"

# Clear stale reports FIRST. `mvn verify` without `clean` leaves every previous run's XML in place,
# and the summary below reads the whole directory — so without this the gate reports results from
# classes that did not run. Measured: a smoke run of 3 classes summarised 50 tests from 12 XML
# files, most of them left by an unrelated probe run. A gate that reports another run's results is
# the same defect as a positive control that ignores its own trigger: it does not just miss
# problems, it reports confident nonsense.
rm -rf "$REPORTS"

START=$SECONDS
mvn "${BASE_ARGS[@]}" "${SELECT[@]}"
STATUS=$?
ELAPSED=$((SECONDS - START))

# Per-class verdict straight from the XML. `verify` already fails the build on test failures, so
# this is for readability, not for trust — but it is also what makes a hung-and-timed-out class
# visible by name rather than as a wall of maven output.
if [ -d "$REPORTS" ]; then
    echo
    echo "--- per-class results ---"
    python3 - "$REPORTS" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
total = failed = 0
for fn in sorted(glob.glob(sys.argv[1] + "/TEST-*.xml")):
    try:
        tree = ET.parse(fn)
    except ET.ParseError:
        print(f"  UNREADABLE (class likely died mid-write): {fn}")
        failed += 1
        continue
    for case in tree.iter("testcase"):
        total += 1
        bad = case.findall("failure") + case.findall("error")
        if bad:
            failed += 1
            print(f"  FAIL {case.get('classname')}#{case.get('name')}")
            print(f"       {(bad[0].get('message') or '')[:200]}")
print(f"  {total} tests, {failed} failed")
PY
fi

echo
if [ $STATUS -eq 0 ]; then
    echo "FORGE GATE PASSED (${ELAPSED}s) — $DESC"
else
    echo "FORGE GATE FAILED (${ELAPSED}s, exit $STATUS) — $DESC"
fi

# Failsafe's forkedProcessTimeoutInSeconds has failed to reap a hung fork more than once, and a
# survivor holds ports that make the NEXT run fail for the wrong reason. Bracket self-exclusion in
# the pattern so this grep does not match itself.
STRAYS=$(pgrep -f 'forge-test[s]' 2>/dev/null | tr '\n' ' ')
if [ -n "${STRAYS// /}" ]; then
    echo
    echo "WARNING: forge JVMs still alive after the run: $STRAYS"
    echo "         They may be shutting down; re-check, and kill them before the next run if they persist."
fi

exit $STATUS
