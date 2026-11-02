#!/usr/bin/env python3
"""Compare the testcases two CI runs executed: the equivalence proof for the build-and-test module groups.

  compare-testcases.py OLD_DIR NEW_DIR      exit 1 unless both hold the same multiset of testcases

Each side is a directory of surefire and failsafe reports laid out as `<module>/target/{surefire,failsafe}-reports/TEST-*.xml`
(the `test-results` artifact). A testcase is (module, classname, name, skipped). It is a multiset, not a set: run
41351583462 held 26,324 <testcase> elements and only 25,957 distinct ones, because parameterized and repeated tests
repeat display names, and a set would hide a lost repeat. Pass or fail is not compared: a flake is not a missing test.
The <testsuite tests="N"> sum is printed beside the element count; it undercounts @Nested classes, so the two differ,
and a report that showed them equal would be counting the attribute.
"""
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path


def testcases(report_dir):
    """(Counter of testcases, report files read, the tests= attribute sum)."""
    cases, files, attribute = Counter(), 0, 0
    for report in sorted(Path(report_dir).rglob("TEST-*.xml")):
        rel = report.relative_to(report_dir).as_posix()
        if "/target/surefire-reports/" not in rel and "/target/failsafe-reports/" not in rel:
            continue
        module = rel.split("/target/")[0]
        suite = ET.parse(report).getroot()
        files += 1
        attribute += int(suite.get("tests", "0"))
        for case in suite.iter("testcase"):
            cases[(module, case.get("classname"), case.get("name"), case.find("skipped") is not None)] += 1
    return cases, files, attribute


def compare(old, new):
    """The differing testcases as (key, old count, new count), sorted."""
    return sorted((key, old[key], new[key]) for key in set(old) | set(new) if old[key] != new[key])


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 2:
        sys.exit(__doc__)
    sides = {}
    for label, directory in zip(("old", "new"), argv):
        cases, files, attribute = testcases(directory)
        if not files:
            sys.exit(f"{label}: no TEST-*.xml under {directory}/<module>/target/*-reports: nothing to compare")
        sides[label] = cases
        print(f"{label}: {files} report file(s), {sum(cases.values())} testcase element(s), {len(cases)} distinct, "
              f"{sum(1 for k in cases.elements() if k[3])} skipped; tests= attribute sum {attribute}", file=sys.stderr)
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
