#!/usr/bin/env python3
"""Compare the testcases two CI runs executed: the equivalence proof for the build-and-test module groups.

  compare-testcases.py OLD_DIR NEW_DIR                 exit 1 unless both hold the same multiset of testcases
  compare-testcases.py --module M OLD_DIR NEW_DIR      the same for one module's flat report artifacts

Each side is a directory of surefire and failsafe reports laid out as `<module>/target/{surefire,failsafe}-reports/TEST-*.xml`
(the `test-results` artifact). A per-module artifact such as `ember-test-results-N` holds its TEST-*.xml at the top level,
because upload-artifact roots it at the reports directory; with --module every TEST-*.xml under the side, at any depth
(one subdirectory per downloaded shard artifact), counts as module M.

A testcase is (module, classname, name, skipped). It is a multiset, not a set: run
41351583462 held 26,324 <testcase> elements and only 25,957 distinct ones, because parameterized and repeated tests
repeat display names, and a set would hide a lost repeat. Pass or fail is not compared: a flake is not a missing test.
The <testsuite tests="N"> sum is printed beside the element count; it undercounts @Nested classes, so the two differ,
and a report that showed them equal would be counting the attribute.
"""
import argparse
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path


def testcases(report_dir, module=None):
    """(Counter of testcases, report files read, the tests= attribute sum); `module` names a flat per-module artifact."""
    cases, files, attribute = Counter(), 0, 0
    for report in sorted(Path(report_dir).rglob("TEST-*.xml")):
        rel = report.relative_to(report_dir).as_posix()
        if module is None and "/target/surefire-reports/" not in rel and "/target/failsafe-reports/" not in rel:
            continue
        owner = module if module is not None else rel.split("/target/")[0]
        suite = ET.parse(report).getroot()
        files += 1
        attribute += int(suite.get("tests", "0"))
        for case in suite.iter("testcase"):
            cases[(owner, case.get("classname"), case.get("name"), case.find("skipped") is not None)] += 1
    return cases, files, attribute


def compare(old, new):
    """The differing testcases as (key, old count, new count), sorted."""
    return sorted((key, old[key], new[key]) for key in set(old) | set(new) if old[key] != new[key])


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--module", help="treat every TEST-*.xml under each side as this module's (flat per-module artifacts)")
    ap.add_argument("old")
    ap.add_argument("new")
    args = ap.parse_args(sys.argv[1:] if argv is None else argv)
    argv = [args.old, args.new]
    sides = {}
    for label, directory in zip(("old", "new"), argv):
        cases, files, attribute = testcases(directory, args.module)
        if not files:
            where = f"{directory} (any depth)" if args.module else f"{directory}/<module>/target/*-reports"
            sys.exit(f"{label}: no TEST-*.xml under {where}: nothing to compare")
        sides[label] = cases
        print(f"{label}: {files} report file(s), {sum(cases.values())} testcase element(s), {len(cases)} distinct, "
              f"{sum(1 for k in cases.elements() if k[3])} skipped; tests= attribute sum {attribute}", file=sys.stderr)
    # #2566's first equivalence run compared a directory with itself and printed "equal": a side inside the other is no proof
    old_dir, new_dir = (Path(d).resolve() for d in argv)
    if old_dir == new_dir or old_dir in new_dir.parents or new_dir in old_dir.parents:
        sys.exit(f"old {old_dir} and new {new_dir} overlap: download each run into its own directory")
    diff = compare(sides["old"], sides["new"])
    per_module = Counter(key[0] for key, _, _ in diff)
    for module, n in sorted(per_module.items()):
        print(f"  {module}: {n} differing testcase(s)", file=sys.stderr)
    for (module, cls, name, skipped), o, n in diff[:50]:
        print(f"  {module} {cls}#{name}{' [skipped]' if skipped else ''}: old {o}, new {n}", file=sys.stderr)
    if diff:
        sys.exit(f"{len(diff)} testcase(s) differ between {argv[0]} and {argv[1]}")
    print("equal: the same testcases ran on both sides", file=sys.stderr)


if __name__ == "__main__":
    main()
