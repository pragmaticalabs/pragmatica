#!/usr/bin/env python3
"""The build-and-test module groups: every reactor module with tests is tested by exactly one CI job.

build-and-test used to run every module's tests in one reactor, where each module's tests waited for its upstream
modules' tests (node started 25 min into a 45 min reactor). The test-group-N jobs each install their modules' upstream
with -DskipTests and test only their own modules, in parallel; the build-and-test job aggregates them.

  ci-test-groups.py check                          every module with tests has exactly one owner; ci.yml agrees; every module with *IT classes binds failsafe
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
# A class tagged with an excluded group at class level runs nothing in a required job (the root pom's excludedGroups), so a
# module whose every test class is tagged so writes no report: it is not a module with tests (ember-shard.py's same rule).
EXCLUDED_CLASS_TAG = re.compile(r'^@Tag\("(Infinite|Slow|Benchmark|S3|quarantine)"\)', re.MULTILINE)


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
    return tests.is_dir() and any(TEST_NAME.match(p.stem) and _runs_tests(p.read_text(errors="replace"))
                                  for p in tests.rglob("*.java"))


def _runs_tests(text):
    return bool(HAS_TESTS.search(text)) and not EXCLUDED_CLASS_TAG.search(text)


def modules_with_tests(root=ROOT):
    return sorted(m for m in reactor_modules(root) if has_tests(root / m))


def _local(element):
    return element.tag.rpartition("}")[2]


def _children(element, name):
    return [c for c in element if _local(c) == name]


# Elements that switch a failsafe run off: `skip`/`skipITs`/`skipTests` set to true (in the plugin or as a pom property), or an
# execution moved to `<phase>none</phase>`.
SKIPS = ("skip", "skipITs", "skipTests")


def _text(element):
    return (element.text or "").strip()


def _active_by_default(profile):
    return any(_text(d) == "true" for a in _children(profile, "activation") for d in _children(a, "activeByDefault"))


def _build_plugins(project):
    """The plugins of <build><plugins> (not pluginManagement) and of activeByDefault profiles' builds. An activeByDefault profile
    stops being active when another profile of the same pom is activated; the guard cannot know which -Ps a build passes."""
    scopes = [project] + [p for ps in _children(project, "profiles") for p in _children(ps, "profile") if _active_by_default(p)]
    return [plugin for scope in scopes for build in _children(scope, "build") for ps in _children(build, "plugins")
            for plugin in _children(ps, "plugin")]


def _failsafe_binding(project, own):
    """The first failsafe plugin entry in the project's build plugins; a parent's entry counts only when inherited."""
    for plugin in _build_plugins(project):
        names = [_text(a) for a in _children(plugin, "artifactId")]
        inherited = [_text(i) for i in _children(plugin, "inherited")]
        if "maven-failsafe-plugin" in names and (own or inherited != ["false"]):
            return plugin
    return None


def _property_skips(project):
    return any(_local(p) in SKIPS and _text(p) == "true" for ps in _children(project, "properties") for p in ps)


def _binding_skips(plugin):
    return any((_local(e) in SKIPS and _text(e) == "true") or (_local(e) == "phase" and _text(e) == "none") for e in plugin.iter())


def _glob(pattern):
    rx = re.escape(pattern).replace("\\*\\*/", "(?:.*/)?").replace("\\*\\*", ".*").replace("\\*", "[^/]*").replace("\\?", "[^/]")
    return re.compile(rx)


def _includes_match(plugin, classes):
    """False when the binding lists explicit <include> patterns and none selects any of the IT classes (paths under src/test/java).
    A %regex[...] or ${property} pattern cannot be evaluated here and counts as matching."""
    patterns = [_text(i) for i in plugin.iter() if _local(i) == "include" and _text(i)]
    return not patterns or any(p.startswith("%") or "${" in p or _glob(p).fullmatch(c) or _glob(p).fullmatch(c[:-len(".java")])
                               for p in patterns for c in classes)


def _its(root, module):
    tests = root / module / "src/test/java"
    return sorted(p.relative_to(tests).as_posix() for p in tests.rglob("*IT.java")) if tests.is_dir() else []


def failsafe_defect(root, module):
    """Why the module's *IT classes would not run, or None: ("unbound", ...) when neither the module's own <build><plugins> (or an
    activeByDefault profile's) nor an inherited parent's entry binds failsafe, so only the root's pluginManagement entry exists and its
    surefire config excludes **/*IT.java; ("disabled", ...) when the nearest binding or a pom property skips it or moves a phase to
    none; ("includes", patterns) when its explicit includes select none of the module's IT classes (#3506)."""
    directory, own, binding, skipped = root / module, True, None, False
    while (directory / "pom.xml").is_file():
        project = ET.parse(directory / "pom.xml").getroot()
        skipped = skipped or _property_skips(project)
        binding = binding if binding is not None else _failsafe_binding(project, own)
        parent = _children(project, "parent")
        if not parent:
            break
        relative = (_children(parent[0], "relativePath") or [None])[0]
        if relative is not None and not _text(relative):
            break  # an empty <relativePath/> tells Maven to resolve the parent from a repository: no local pom to read
        directory = (directory / (_text(relative) if relative is not None else "..")).resolve()
        directory = directory.parent if directory.name == "pom.xml" else directory
        own = False
    if binding is None:
        return "unbound", ""
    if skipped or _binding_skips(binding):
        return "disabled", ""
    its = _its(root, module)
    if not _includes_match(binding, its):
        return "includes", ", ".join(_text(i) for i in binding.iter() if _local(i) == "include")
    return None


def modules_with_unbound_its(root=ROOT):
    """Reactor modules with a src/test/java/**/*IT.java class where failsafe will not run (#3506)."""
    return sorted(m for m in reactor_modules(root) if _its(root, m) and (failsafe_defect(root, m) or ("",))[0] == "unbound")


def misnamed_its(root=ROOT):
    """(module, path) of IT*.java and *ITCase.java classes in reactor modules: the root failsafe includes only **/*IT.java."""
    found = []
    for module in reactor_modules(root):
        tests = root / module / "src/test/java"
        if tests.is_dir():
            found += [(module, p.relative_to(tests).as_posix()) for p in tests.rglob("*.java")
                      if re.fullmatch(r"IT.*|.*ITCase", p.stem)]
    return sorted(found)


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
    for module in reactor_modules(root):
        defect = failsafe_defect(root, module) if _its(root, module) else None
        if defect and defect[0] == "unbound":
            problems.append(f"{module} has *IT classes and no maven-failsafe-plugin binding in its <build><plugins> or an inherited "
                            f"parent's: they run in no build (surefire excludes *IT.java)")
        elif defect and defect[0] == "disabled":
            problems.append(f"{module} has *IT classes and its maven-failsafe-plugin binding is disabled (skip, skipITs, skipTests "
                            f"or phase none): they run in no build")
        elif defect:
            problems.append(f"{module} has *IT classes and its maven-failsafe-plugin includes [{defect[1]}] select none of them: "
                            f"they run in no build")
    problems += [f"{module}/src/test/java/{path} is named IT*/*ITCase, which the failsafe includes (**/*IT.java) never select: "
                 f"it runs in no build" for module, path in misnamed_its(root)]
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
    """A test group's modules; a `job:` owner is not a group, so it names none."""
    return [] if group.startswith("job:") else [m for o, m in rows if o == group]


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
        time = ET.parse(report).getroot().get("time")
        if time is None:
            sys.exit(f"{report}: <testsuite> has no time attribute; refusing to weigh its module at 0 s")
        seconds[module] = seconds.get(module, 0.0) + float(time.replace(",", ""))
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
