#!/usr/bin/env python3
"""Split aether/ember's test classes across CI shards (#1643).

Ember's tests run serially in one JVM and take ~54 min, more than a CI job should spend. Surefire cannot shard, so
each shard job runs `mvn test -Dtest=<this script's output>`.

  ember-shard.py --shards N --index I              comma-separated classes of shard I (1-based) on stdout
  ember-shard.py --shards N --index I --verify D   check shard I's classes each wrote a TEST-*.xml in report dir D

The classes are read from the source tree, never from a list, so a new test class lands in some shard by construction.
Weights (tools/ember-shard-weights.txt) only balance; a class without one gets the DEFAULT weight, the largest measured
class, so a new class can lengthen a shard by its real time at most and the budget check fails the job early if a
shard's predicted total exceeds BUDGET_SECONDS, telling the maintainer to raise --shards.
"""
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEST_DIR = ROOT / "aether/ember/src/test/java"
WEIGHTS = ROOT / "tools/ember-shard-weights.txt"
# surefire's default includes, minus *IT (the root pom excludes it from surefire)
TEST_NAME = re.compile(r"^(Test.*|.*Test|.*Tests|.*TestCase)$")
# Shard test time must stay under this: job timeout 45 min - ~2.5 min setup, / 1.3 headroom, rounded down.
BUDGET_SECONDS = 1920


def test_classes(test_dir=TEST_DIR):
    return sorted({p.stem for p in test_dir.rglob("*.java") if TEST_NAME.match(p.stem)})


def read_weights(path=WEIGHTS):
    weights = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            name, seconds = line.split()
            weights[name] = int(seconds)
    return weights


def default_weight(weights):
    return max(weights.values())


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
    ap.add_argument("--shards", type=int, required=True)
    ap.add_argument("--index", type=int, required=True)
    ap.add_argument("--verify", metavar="REPORT_DIR")
    args = ap.parse_args(argv)
    if args.shards < 1 or not 1 <= args.index <= args.shards:
        ap.error("need 1 <= index <= shards")
    classes, weights = test_classes(), read_weights()
    if not classes:
        sys.exit(f"no Ember test classes under {TEST_DIR}: refusing to run a shard that selects nothing")
    bins = assign(classes, weights, args.shards)
    mine = bins[args.index - 1]
    for i, b in enumerate(bins, 1):
        print(f"shard {i}/{args.shards}: {len(b['classes'])} classes, predicted {b['total'] / 60:.1f} min"
              f"{'  <- this shard' if i == args.index else ''}", file=sys.stderr)
    unweighted = [c for c in classes if c not in weights]
    if unweighted:
        print(f"unweighted classes (default {default_weight(weights)} s each): {', '.join(unweighted)}", file=sys.stderr)
    if max(b["total"] for b in bins) > BUDGET_SECONDS:
        sys.exit(f"a shard is predicted over {BUDGET_SECONDS / 60:.0f} min: raise the matrix shard count "
                 f"(and add measured weights to tools/ember-shard-weights.txt)")
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
