#!/usr/bin/env python3
"""Split a module's test classes across CI shards (#1643 Ember, #2414 Consensus).

Ember's tests run serially in one JVM and take ~54 min, more than a CI job should spend; Consensus's take ~18 min on the
critical path of build-and-test. Surefire cannot shard, so each shard job runs `mvn test -Dtest=<this script's output>`.

  ember-shard.py [--module M] --shards N --index I              comma-separated classes of shard I (1-based) on stdout
  ember-shard.py [--module M] --shards N --index I --verify D   check shard I's classes each wrote a TEST-*.xml in report dir D

M is `ember` (default) or `consensus`; each has its own source tree, weights file and budget.

The classes are read from the source tree, never from a list, so a new test class lands in some shard by construction.
Weights (tools/ember-shard-weights.txt) only balance, and a new class needs none: it gets the DEFAULT weight, the 75th
percentile of the module's measured weights (default_weight), so a PR adding a test class never edits the weights file
(#2414's per-class rule made that file a merge hotspot). The budget check still fails the job early if a shard's
predicted total, defaults included, exceeds BUDGET_SECONDS, telling the maintainer to raise --shards. The weights
regenerate from a CI run's own reports with tools/regen-shard-weights.py rather than by hand-added lines.

The default is a percentile, not the maximum, because the maximum (Ember's 624 s class) made one new class a predicted
overrun of a plan that had 104 s of headroom. The cost: a new class slower than the percentile is under-predicted until
the next regeneration, and the only margin for that error is the slack between a budget and its step timeout (Ember:
1920 s against the test step's 40 min, 480 s).
"""
import argparse
import math
import re
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# module -> (test source dir, weights file, shard budget in seconds)
MODULES = {
    "ember": (ROOT / "aether/ember/src/test/java", ROOT / "tools/ember-shard-weights.txt", 1920),
    # job timeout 30 min - ~4 min setup (bootstrap + upstream install), / 1.3 headroom, rounded down.
    "consensus": (ROOT / "integrations/consensus/src/test/java", ROOT / "tools/consensus-shard-weights.txt", 1200),
}
TEST_DIR, WEIGHTS, BUDGET_SECONDS = MODULES["ember"]
# surefire's default includes, minus *IT (the root pom excludes it from surefire)
TEST_NAME = re.compile(r"^(Test.*|.*Test|.*Tests|.*TestCase)$")
# Ember's shard budget (MODULES): job timeout 45 min - ~2.5 min setup, / 1.3 headroom, rounded down.
# A top-level class tag in the root pom's surefire.excludedGroups: surefire selects the class and then runs nothing, so it
# writes no report and a shard assigned it would fail --verify for a class that correctly did not run.
EXCLUDED_CLASS_TAG = re.compile(r'^@Tag\("(Infinite|Slow|Benchmark|S3)"\)', re.MULTILINE)
# A class with none of these is a fixture that only matches the name pattern (Test*): it writes no report either.
HAS_TESTS = re.compile(r"@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b")


def runs_tests(path):
    text = path.read_text()
    return bool(HAS_TESTS.search(text)) and not EXCLUDED_CLASS_TAG.search(text)


def test_classes(test_dir=TEST_DIR):
    return sorted({p.stem for p in test_dir.rglob("*.java") if TEST_NAME.match(p.stem) and runs_tests(p)})


def read_weights(path=WEIGHTS):
    weights = {}
    for line in path.read_text().splitlines():
        line = line.split("#", 1)[0].strip()  # a weight line may carry its provenance as a trailing comment
        if line:
            name, seconds = line.split()
            weights[name] = int(seconds)
    return weights


def default_weight(weights):
    """The weight of a class the weights file does not list: the 75th percentile of the listed ones, rounded up."""
    values = sorted(weights.values())
    if len(values) < 2:
        return values[0] if values else 0
    return math.ceil(statistics.quantiles(values, n=4, method="inclusive")[2])


def assign(classes, weights, shards):
    """Longest-processing-time first; ties broken by name so the result is deterministic."""
    fallback = default_weight(weights)
    ordered = sorted(classes, key=lambda c: (-weights.get(c, fallback), c))
    bins = [{"total": 0, "classes": []} for _ in range(shards)]
    for c in ordered:
        target = min(bins, key=lambda b: b["total"])
        target["classes"].append(c)
        target["total"] += weights.get(c, fallback)
    for b in bins:
        b["classes"].sort()
    return bins


def verify(report_dir, classes):
    """Every assigned class must have written a report; zero reports is a shard that tested nothing."""
    present = {p.name[len("TEST-"):-len(".xml")].rsplit(".", 1)[-1].split("$")[0]
               for p in Path(report_dir).glob("TEST-*.xml")}
    return [c for c in classes if c not in present], len(present)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--module", choices=sorted(MODULES), default="ember")
    ap.add_argument("--shards", type=int, required=True)
    ap.add_argument("--index", type=int, required=True)
    ap.add_argument("--verify", metavar="REPORT_DIR")
    args = ap.parse_args(argv)
    if args.shards < 1 or not 1 <= args.index <= args.shards:
        ap.error("need 1 <= index <= shards")
    test_dir, weights_file, budget = MODULES[args.module]
    classes, weights = test_classes(test_dir), read_weights(weights_file)
    if not classes:
        sys.exit(f"no {args.module} test classes under {test_dir}: refusing to run a shard that selects nothing")
    bins = assign(classes, weights, args.shards)
    mine = bins[args.index - 1]
    for i, b in enumerate(bins, 1):
        print(f"shard {i}/{args.shards}: {len(b['classes'])} classes, predicted {b['total'] / 60:.1f} min"
              f"{'  <- this shard' if i == args.index else ''}", file=sys.stderr)
    unweighted = [c for c in classes if c not in weights]
    if unweighted:
        print(f"unweighted classes (default {default_weight(weights)} s each): {', '.join(unweighted)}", file=sys.stderr)
    if max(b["total"] for b in bins) > budget:
        sys.exit(f"a shard is predicted over {budget / 60:.0f} min ({len(unweighted)} class(es) at the default weight): "
                 f"raise the matrix shard count, or regenerate tools/{args.module}-shard-weights.txt from a CI run "
                 f"with tools/regen-shard-weights.py if the defaults overstate the new classes")
    if not mine["classes"]:
        sys.exit(f"shard {args.index} has no classes: lower the shard count")
    if args.verify:
        missing, n = verify(args.verify, mine["classes"])
        print(f"{n} report file(s) in {args.verify}", file=sys.stderr)
        if n == 0 or missing:
            sys.exit(f"shard {args.index} did not run: {', '.join(missing) or 'no reports at all'}")
        return
    print(",".join(mine["classes"]))


if __name__ == "__main__":
    main()
