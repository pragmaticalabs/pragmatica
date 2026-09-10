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
# the runtime, it only runs the gate. It does now REFUSE to run against a stale one (#865):
# `-pl` without `-am` resolves every sibling from the local Maven repository, so the gate can
# report either verdict against bytecode that is not this tree's. FORGE_SKIP_FRESHNESS=1
# overrides, and says so in the output.
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

# --- runtime freshness gate (#865) --------------------------------------------------------------
# BASE_ARGS deliberately carries no `-am` (see the note above it), so every sibling module resolves
# from the local Maven repository and this gate exercises whatever is INSTALLED there. #858 lost a
# session to 15 of 16 classes "failing" against a node jar built the previous evening; the
# symmetric stale GREEN is worse, because it certifies a fix that was never executed.
#
# Two different silences are distinguished on purpose, because both look like a pass. A checker that
# is ABSENT has not checked anything, so a missing script refuses exactly as a stale tree does - and
# the checker itself exits 2, never 0, when it examined nothing.
FRESHNESS="$REPO_ROOT/tools/forge-freshness.py"

echo "--- runtime freshness (#865) ---"
if [ "${FORGE_SKIP_FRESHNESS:-0}" != "0" ]; then
    echo "  SKIPPED - FORGE_SKIP_FRESHNESS is set."
    echo "  This run is NOT evidence about this tree: it exercises whatever happens to be installed."
elif [ ! -f "$FRESHNESS" ]; then
    echo "  FRESHNESS CHECK DID NOT RUN - $FRESHNESS is missing."
    echo "  A checker that did not run is not a checker that passed."
    echo "  Restore it, or set FORGE_SKIP_FRESHNESS=1 to run the gate unverified."
    exit 2
else
    python3 "$FRESHNESS" "$REPO_ROOT"
    FRESHNESS_STATUS=$?
    if [ $FRESHNESS_STATUS -eq 1 ]; then
        echo
        echo "FORGE GATE REFUSED TO RUN - the runtime is STALE (modules named above)."
        echo "  Run ./build.sh (or mvn install -DskipTests) and start this gate again."
        echo "  FORGE_SKIP_FRESHNESS=1 overrides; the run then proves nothing about those modules."
        exit 1
    elif [ $FRESHNESS_STATUS -ne 0 ]; then
        echo
        echo "FORGE GATE REFUSED TO RUN - freshness is UNDETERMINED (checker exit $FRESHNESS_STATUS)."
        echo "  An undetermined result is not a pass."
        echo "  FORGE_SKIP_FRESHNESS=1 overrides; the run then proves nothing about this tree."
        exit $FRESHNESS_STATUS
    fi
fi
echo

REPORTS="$MODULE/target/failsafe-reports"

# Clear stale reports FIRST. `mvn verify` without `clean` leaves every previous run's XML in place,
# and the summary below reads the whole directory — so without this the gate reports results from
# classes that did not run. Measured: a smoke run of 3 classes summarised 50 tests from 12 XML
# files, most of them left by an unrelated probe run. A gate that reports another run's results is
# the same defect as a positive control that ignores its own trigger: it does not just miss
# problems, it reports confident nonsense.
rm -rf "$REPORTS"

START=$SECONDS
# `env -u HCLOUD_TOKEN` is a MECHANISM, not a convention. `verify` is the correct phase here (only
# `verify` enforces failsafe failures; `integration-test` prints BUILD SUCCESS over failing tests),
# and the hard-coded `-pl aether/forge/forge-tests` is what keeps HetznerCloudIT — which provisions
# a real paid server when HCLOUD_TOKEN is set — out of the reactor. But that safety currently rests
# on a future reader preserving the scope. Stripping the token means the day someone widens `-pl`
# or adds `-am`, the Hetzner IT fails LOUDLY for want of a credential instead of quietly billing
# someone. The script guarantees the invariant rather than asking to be trusted with it.
env -u HCLOUD_TOKEN mvn "${BASE_ARGS[@]}" "${SELECT[@]}"
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
