#!/usr/bin/env python3
"""Flaky-test quarantine (CTO ruling 2026-11-02, know 5544ec434b): the guard and the quarantine job's selector.

A flaky test is tagged `@Tag("quarantine")`, on the method, or on the class only when the whole class flakes. Every
required job excludes the tag; the non-required `quarantine` job in ci.yml runs exactly the tagged tests on every push.

  check-quarantine.py check                    offline: every tag cites an issue; every exclusion carries the tag
  check-quarantine.py issues --repo OWNER/NAME  online: every cited issue is still open (GitHub API, GH_TOKEN)
  check-quarantine.py modules                  the modules holding a quarantined test, comma-separated (may be empty)

`check` gates (static-gates, required) and reads nothing but the checkout, so GitHub's availability cannot fail it.
`issues` runs in the non-required quarantine job: a tag citing a closed issue turns that job red, and an API error
there is reported as "could not look", never as a pass, and never reaches a required check.

check fails on:
  - a quarantine tag with no issue reference (`#<number>`) in a `//` comment on its own line or in the comment and
    annotation lines directly above it;
  - a non-canonical spelling (`@Tag("Quarantine")`, `@Tag(value = "quarantine")`): the guard and the exclusions match
    the canonical form only, so a variant would be a quarantine nobody checks;
  - a surefire or failsafe `<excludedGroups>` in any pom.xml, or an `excludedGroups=` override in a ci.yml job other than
    `quarantine`, that does not exclude `quarantine`: that job would run, and gate on, the quarantined tests;
  - a root or aether pluginManagement failsafe configuration without an `<excludedGroups>`: failsafe excludes nothing
    by default, so every module inheriting it would run quarantined ITs in its required job.
"""
import argparse
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TAG = '@Tag("quarantine")'
ANY_SPELLING = re.compile(r'@Tag\s*\(\s*(?:value\s*=\s*)?"quarantine"\s*\)', re.IGNORECASE)
ISSUE = re.compile(r"#(\d+)")
PRUNED = {"target", ".git", ".m2-local", "node_modules"}
PLUGIN = re.compile(r"<plugin>\s*<groupId>org\.apache\.maven\.plugins</groupId>\s*"
                    r"<artifactId>maven-(surefire|failsafe)-plugin</artifactId>(.*?)</plugin>", re.S)
# The pluginManagement blocks every failsafe user inherits from: the root's, and aether/'s own.
FAILSAFE_PARENTS = ("pom.xml", "aether/pom.xml")


def test_sources(root=None):
    """Every .java under a src/test directory, build output and VCS internals pruned."""
    root = root or ROOT
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in PRUNED]
        if "/src/test/" in f"{dirpath}/".replace(os.sep, "/"):
            for name in filenames:
                if name.endswith(".java"):
                    yield Path(dirpath) / name


def tags(root=None):
    """(file, 1-based line, issue numbers referenced, canonical) for every quarantine tag."""
    root = root or ROOT
    found = []
    for path in sorted(test_sources(root)):
        lines = path.read_text(errors="replace").splitlines()
        for i, line in enumerate(lines):
            if not ANY_SPELLING.search(line):
                continue
            context = [line]
            j = i - 1
            while j >= 0 and lines[j].strip().startswith(("//", "@")):  # the comment and annotation lines directly above
                context.append(lines[j])
                j -= 1
            issues = sorted({int(n) for text in context if "//" in text for n in ISSUE.findall(text[text.index("//"):])})
            found.append((path, i + 1, issues, TAG in line))
    return found


def _properties(pom_text):
    block = re.search(r"<properties>(.*?)</properties>", re.sub(r"<!--.*?-->", "", pom_text, flags=re.S), re.S)
    return dict(re.findall(r"<([\w.-]+)>([^<]*)</\1>", block.group(1))) if block else {}


def _resolve(value, *property_maps):
    def one(match):
        for props in property_maps:
            if match.group(1) in props:
                return props[match.group(1)]
        return match.group(0)
    return re.sub(r"\$\{([\w.-]+)\}", one, value)


def _excludes_quarantine(value):
    return "quarantine" in [g.strip() for g in value.split(",")]


def exclusion_problems(root=None):
    root = root or ROOT
    problems = []
    root_props = _properties((root / "pom.xml").read_text())
    for pom in sorted(root.rglob("pom.xml")):
        if PRUNED & set(pom.relative_to(root).parts):
            continue
        text = re.sub(r"<!--.*?-->", "", pom.read_text(), flags=re.S)
        props = _properties(text)
        rel = pom.relative_to(root).as_posix()
        for kind, body in PLUGIN.findall(text):
            for value in re.findall(r"<excludedGroups>([^<]*)</excludedGroups>", body):
                resolved = _resolve(value, props, root_props)
                if not _excludes_quarantine(resolved):
                    problems.append(f"{rel}: maven-{kind}-plugin <excludedGroups>{value}</excludedGroups> resolves to "
                                    f"'{resolved}', which does not exclude quarantine")
    for rel in FAILSAFE_PARENTS:
        text = re.sub(r"<!--.*?-->", "", (root / rel).read_text(), flags=re.S)
        managed = re.search(r"<pluginManagement>(.*?)</pluginManagement>", text, re.S)
        failsafe = [body for kind, body in PLUGIN.findall(managed.group(1) if managed else "") if kind == "failsafe"]
        if not any("<excludedGroups>" in body for body in failsafe):
            problems.append(f"{rel}: pluginManagement maven-failsafe-plugin has no <excludedGroups>, so its users run "
                            f"quarantined ITs")
    return problems


def workflow_problems(ci_text):
    """An excludedGroups override in any ci.yml job but `quarantine` must keep quarantine excluded."""
    problems = []
    jobs = re.split(r"^  (?=[A-Za-z0-9_-]+:\s*$)", ci_text[re.search(r"^jobs:", ci_text, re.MULTILINE).end():],
                    flags=re.MULTILINE)
    for job in jobs:
        name = job.split(":", 1)[0].strip()
        if not name or name == "quarantine":
            continue
        for prop, value in re.findall(r"-D([\w.-]*excludedGroups)=(\S*)", job):
            if not _excludes_quarantine(value):
                problems.append(f"ci.yml job {name}: -D{prop}={value} does not exclude quarantine")
    return problems


def open_issue(repo, number):
    """True when the issue is open; GH_TOKEN (CI's GITHUB_TOKEN) authenticates the read."""
    request = urllib.request.Request(f"https://api.github.com/repos/{repo}/issues/{number}",
                                     headers={"Accept": "application/vnd.github+json",
                                              **({"Authorization": f"Bearer {os.environ['GH_TOKEN']}"}
                                                 if os.environ.get("GH_TOKEN") else {})})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response).get("state") == "open"


def check(root=None, ci_file=None):
    """The offline problems: they depend on the checkout alone, so a required job may gate on them."""
    root = root or ROOT
    problems = []
    for path, line, issues, canonical in tags(root):
        where = f"{path.relative_to(root).as_posix()}:{line}"
        if not canonical:
            problems.append(f"{where}: write the quarantine tag exactly as {TAG}")
        if not issues:
            problems.append(f"{where}: a quarantine tag needs its open issue: `// quarantine: #<issue> <why>` on the "
                            f"line above it")
    problems += exclusion_problems(root)
    problems += workflow_problems((ci_file or root / ".github/workflows/ci.yml").read_text())
    return problems


def closed_issue_problems(is_open, root=None):
    """The online problem: a tag citing an issue that is no longer open. Only the non-required quarantine job asks."""
    root = root or ROOT
    problems = []
    for path, line, issues, _ in tags(root):
        for number in issues:
            if not is_open(number):
                problems.append(f"{path.relative_to(root).as_posix()}:{line}: #{number} is not an open issue; fix the "
                                f"test and remove the tag, or cite the open issue that tracks it")
    return problems


def quarantined_modules(root=None):
    """The module directories (relative to root) holding a quarantine tag: the directory above src/test."""
    root = root or ROOT
    modules = set()
    for path, _, _, _ in tags(root):
        rel = path.relative_to(root).as_posix()
        modules.add(rel.split("/src/test/")[0])
    return sorted(modules)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command", required=True)
    sub.add_parser("check")
    sub.add_parser("issues").add_argument("--repo", required=True, help="OWNER/NAME of the issue tracker")
    sub.add_parser("modules")
    args = ap.parse_args(argv)
    if args.command == "modules":
        print(",".join(quarantined_modules()))
        return
    found = tags()
    if args.command == "issues":
        try:
            problems = closed_issue_problems(lambda n: open_issue(args.repo, n))
        except OSError as e:  # URLError and HTTPError are OSErrors: the API did not answer, so nothing was looked at
            sys.exit(f"quarantine: could not look up the cited issues ({e}); this job reports, it does not gate")
        print(f"quarantine: {len(found)} tag(s), {sum(len(i) for _, _, i, _ in found)} issue reference(s), "
              f"{len(problems)} closed", file=sys.stderr)
    else:
        problems = check()
        print(f"quarantine: {len(found)} tag(s) in {len(quarantined_modules())} module(s), {len(problems)} problem(s)",
              file=sys.stderr)
    if problems:
        sys.exit("\n".join(problems))


if __name__ == "__main__":
    main()
