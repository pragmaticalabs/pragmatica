#!/usr/bin/env python3
"""The build-and-test module groups: every reactor module with tests is tested by exactly one CI job.

build-and-test used to run every module's tests in one reactor, where each module's tests waited for its upstream
modules' tests (node started 25 min into a 45 min reactor). The test-group-N jobs each install their modules' upstream
with -DskipTests and test only their own modules, in parallel; the build-and-test job aggregates them.

  ci-test-groups.py check                          every module with tests has exactly one owner; ci.yml agrees
  ci-test-groups.py modules --group G              G's modules, comma-separated, for `mvn -pl`
  ci-test-groups.py has-examples --group G         `yes` when G runs the standalone examples build, else `no`
  ci-test-groups.py verify-reports DIR             every grouped module wrote a report under DIR (merged artifacts)
  ci-test-groups.py regenerate --dir D --run R [--groups N]   rebalance the groups from run R's reports under D

tools/ci-test-groups.txt has one line per module: `<owner> <module path>`. An owner is a test group (a test-group matrix
entry in ci.yml) or `job:<ci.yml job id>` for a module another job tests (ember-shard, consensus-shard).
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GROUPS = ROOT / "tools/ci-test-groups.txt"
CI = ROOT / ".github/workflows/ci.yml"
# The standalone `mvn verify -f examples/pom.xml` runs in the group holding these modules, so they stay together.
EXAMPLES = "examples/"
# surefire's default includes plus failsafe's (*IT, IT*, *ITCase): a module whose only tests are ITs still has tests.
TEST_NAME = re.compile(r"^(Test.*|.*Test|.*Tests|.*TestCase|.*IT|IT.*|.*ITCase)$")
HAS_TESTS = re.compile(r"@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b")


def _pom_modules(text):
    """A pom's default <modules>: comments and <profiles> removed, as the default reactor reads them."""
    text = re.sub(r"<profiles>.*?</profiles>", "", re.sub(r"<!--.*?-->", "", text, flags=re.S), flags=re.S)
    block = re.search(r"<modules>(.*?)</modules>", text, re.S)
    return re.findall(r"<module>\s*([^<\s]+)\s*</module>", block.group(1)) if block else []


def reactor_modules(root=ROOT, module=""):
    """Every project of the default reactor, as paths relative to the root ("" is the root itself)."""
    found = [module]
    for child in _pom_modules((root / module / "pom.xml").read_text()):
        found += reactor_modules(root, f"{module}/{child}" if module else child)
    return found


def has_tests(module_dir):
    tests = module_dir / "src/test/java"
    return tests.is_dir() and any(TEST_NAME.match(p.stem) and HAS_TESTS.search(p.read_text(errors="replace"))
                                  for p in tests.rglob("*.java"))


def modules_with_tests(root=ROOT):
    return sorted(m for m in reactor_modules(root) if m and has_tests(root / m))


def read_groups(path=GROUPS):
    """[(owner, module)] in file order; a trailing `# ...` is provenance."""
    rows = []
    for line in path.read_text().splitlines():
        body = line.split("#", 1)[0].strip()
        if body:
            owner, module = body.split()
            rows.append((owner, module))
    return rows


def matrix_groups(ci_text):
    """The test-group job's matrix list in ci.yml."""
    job = re.search(r"^  test-group:\n(.*?)(?=^  \S|\Z)", ci_text, re.MULTILINE | re.DOTALL)
    if not job:
        return []
    listed = re.search(r"group: \[([^\]]*)\]", job.group(1))
    return [g.strip() for g in listed.group(1).split(",")] if listed else []


def ci_jobs(ci_text):
    jobs = ci_text[re.search(r"^jobs:", ci_text, re.MULTILINE).start():]
    return set(re.findall(r"^  ([A-Za-z0-9_-]+):\s*$", jobs, re.MULTILINE))


def check(root=ROOT, groups_file=GROUPS, ci_file=CI):
    """The problems that make the grouping unsound; empty when every module with tests has exactly one owner."""
    rows, ci_text = read_groups(groups_file), ci_file.read_text()
    problems = []
    tested = modules_with_tests(root)
    owners = {}
    for owner, module in rows:
        owners.setdefault(module, []).append(owner)
    for module in tested:
        if module not in owners:
            problems.append(f"{module} has tests and no owner: add `<group> {module}` to {groups_file.name}")
        elif len(owners[module]) > 1:
            problems.append(f"{module} has {len(owners[module])} owners ({', '.join(owners[module])}): it would run twice")
    for module in sorted(set(owners) - set(tested)):
        problems.append(f"{module} is listed but is not a default-reactor module with tests")
    groups = sorted({o for o, _ in rows if not o.startswith("job:")})
    matrix = matrix_groups(ci_text)
    if sorted(matrix) != groups:
        problems.append(f"ci.yml test-group matrix {sorted(matrix)} differs from the groups in {groups_file.name} {groups}")
    jobs = ci_jobs(ci_text)
    for owner in sorted({o for o, _ in rows if o.startswith("job:")}):
        if owner[len("job:"):] not in jobs:
            problems.append(f"{owner} names no ci.yml job")
    example_groups = sorted({o for o, m in rows if m.startswith(EXAMPLES)})
    if len(example_groups) > 1:
        problems.append(f"examples modules are split across {example_groups}: the standalone examples build needs one group")
    if "maven.test.skip" in ci_text:
        problems.append("ci.yml uses maven.test.skip, which drops the statemachine test-jar that five modules test-depend on")
    return problems


def group_modules(group, rows):
    return [m for o, m in rows if o == group]


def verify_reports(report_dir, rows):
    """(problems, per-group (modules, testcases)) for the merged group artifacts under report_dir."""
    problems, totals = [], {}
    for owner, module in rows:
        if owner.startswith("job:"):
            continue
        reports = [p for kind in ("surefire-reports", "failsafe-reports")
                   for p in (Path(report_dir) / module / "target" / kind).glob("TEST-*.xml")]
        if not reports:
            problems.append(f"{module} (group {owner}) wrote no TEST-*.xml: its tests did not run")
        cases = sum(1 for p in reports for _ in ET.parse(p).getroot().iter("testcase"))
        modules, count = totals.get(owner, (0, 0))
        totals[owner] = (modules + 1, count + cases)
    return problems, totals


def module_seconds(report_dir):
    """Summed <testsuite time> per module, from TEST-*.xml under <dir>/<module>/target/{surefire,failsafe}-reports."""
    seconds = {}
    for report in Path(report_dir).rglob("TEST-*.xml"):
        rel = report.relative_to(report_dir).as_posix()
        if "/target/" not in rel:
            continue
        module = rel.split("/target/")[0]
        seconds[module] = seconds.get(module, 0.0) + float(ET.parse(report).getroot().get("time").replace(",", ""))
    return seconds


def regenerate(rows, seconds, groups, run):
    """Longest-processing-time first over the grouped modules; the examples modules move as one unit."""
    owned = [(o, m) for o, m in rows if o.startswith("job:")]
    units = {}
    for _, module in [(o, m) for o, m in rows if not o.startswith("job:")]:
        units.setdefault("examples" if module.startswith(EXAMPLES) else module, []).append(module)
    weight = {u: sum(seconds.get(m, 0.0) for m in ms) for u, ms in units.items()}
    bins = [{"total": 0.0, "modules": []} for _ in range(groups)]
    for unit in sorted(units, key=lambda u: (-weight[u], u)):
        target = min(bins, key=lambda b: b["total"])
        target["modules"] += units[unit]
        target["total"] += weight[unit]
    bins.sort(key=lambda b: -b["total"])
    out = [f"# build-and-test module groups: LPT over summed surefire+failsafe suite time per module from run {run}.\n"
           f"# Generated by tools/ci-test-groups.py regenerate; checked by tools/ci-test-groups.py check.\n"]
    for i, b in enumerate(bins, 1):
        out.append(f"# g{i}: {b['total'] / 60:.1f} min serial, {len(b['modules'])} modules\n")
    for i, b in enumerate(bins, 1):
        for m in sorted(b["modules"]):
            out.append(f"g{i} {m}  # {seconds.get(m, 0.0):.1f} s at run {run}\n")
    for owner, module in owned:
        out.append(f"{owner} {module}\n")
    return "".join(out), [b["total"] for b in bins]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command", required=True)
    sub.add_parser("check")
    for name in ("modules", "has-examples"):
        sub.add_parser(name).add_argument("--group", required=True)
    sub.add_parser("verify-reports").add_argument("dir")
    regen = sub.add_parser("regenerate")
    regen.add_argument("--dir", required=True)
    regen.add_argument("--run", required=True)
    regen.add_argument("--groups", type=int, default=4)
    args = ap.parse_args(argv)
    rows = read_groups()
    if args.command == "check":
        problems = check()
        print(f"ci-test-groups: {len(modules_with_tests())} module(s) with tests, {len(rows)} owner line(s), "
              f"{len(problems)} problem(s)", file=sys.stderr)
        if problems:
            sys.exit("\n".join(problems))
    elif args.command in ("modules", "has-examples"):
        modules = group_modules(args.group, rows)
        if not modules:
            sys.exit(f"group {args.group} has no modules in {GROUPS}")
        print(",".join(modules) if args.command == "modules"
              else ("yes" if any(m.startswith(EXAMPLES) for m in modules) else "no"))
    elif args.command == "verify-reports":
        problems, totals = verify_reports(args.dir, rows)
        for group, (modules, cases) in sorted(totals.items()):
            print(f"{group}: {modules} module(s), {cases} testcase(s)", file=sys.stderr)
        print(f"total: {sum(c for _, c in totals.values())} testcase(s)", file=sys.stderr)
        if problems:
            sys.exit("\n".join(problems))
    else:
        seconds = module_seconds(args.dir)
        if not seconds:
            sys.exit(f"no module reports under {args.dir}: refusing to rewrite {GROUPS} from nothing")
        text, totals = regenerate(rows, seconds, args.groups, args.run)
        GROUPS.write_text(text)
        print(f"{GROUPS}: {args.groups} groups, {', '.join(f'{t / 60:.1f}' for t in totals)} min serial", file=sys.stderr)


if __name__ == "__main__":
    main()
