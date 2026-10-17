#!/usr/bin/env python3
# forge-freshness.py — refuse to run the forge gate against stale runtime bytecode (#865).
#
# forge.sh runs `mvn verify -pl aether/forge/forge-tests` with NO `-am`, deliberately: the hard-coded
# single-module scope is what keeps HetznerCloudIT (which provisions a real paid server) out of the
# reactor. The price is that every sibling module resolves from the local Maven repository, so the
# gate executes whatever bytecode happens to be installed there rather than the tree's. That has
# already produced a false NEGATIVE — #858 saw 15 of 16 classes "fail" against a node jar built the
# previous evening, before the fix commits. The symmetric case is worse and silent: a stale GREEN
# certifies a fix that was never executed.
#
# This script is itself an instrument, so it is built so that it cannot report success without having
# looked:
#   * it prints how many modules it examined, and that COUNT is the evidence — not the exit status;
#   * examining zero modules is exit 2 with a message saying the result means nothing, never a pass;
#   * NOT INSTALLED is counted and named separately from FRESH. An absent artifact is not evidence of
#     freshness, and collapsing the two is exactly how a gate reports green having checked nothing.
#
# Scope is forge-tests' intra-repo dependency CLOSURE, computed from the poms, not "every module":
# those are the artifacts the no-`-am` run actually resolves. The closure size is printed so a reader
# can see the space that was searched rather than infer it from a verdict.
#
# Usage:  forge-freshness.py <repo-root> [<local-repo>]
# Exit:   0 fresh   1 stale (names every stale module)   2 indeterminate (examined nothing)

import os
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
PRUNE = {".git", "target", ".m2-local", ".idea", "node_modules"}
FORGE_TESTS = "aether/forge/forge-tests"

EXIT_FRESH = 0
EXIT_STALE = 1
EXIT_INDETERMINATE = 2


def stamp(epoch_seconds):
    return datetime.fromtimestamp(epoch_seconds, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def discover_poms(root):
    found = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in PRUNE]
        if "pom.xml" in filenames:
            found.append(Path(dirpath) / "pom.xml")
    return found


def text_of(element, path):
    node = element.find(path, NS)
    return node.text.strip() if node is not None and node.text else None


def parse_pom(pom_path, root):
    try:
        project = ET.parse(pom_path).getroot()
    except ET.ParseError as error:
        return {"path": pom_path, "broken": str(error)}

    parent = project.find("m:parent", NS)
    group = text_of(project, "m:groupId") or (text_of(parent, "m:groupId") if parent is not None else None)
    version = text_of(project, "m:version") or (text_of(parent, "m:version") if parent is not None else None)
    artifact = text_of(project, "m:artifactId")

    # dependencyManagement is deliberately NOT read: it declares versions for artifacts a module may
    # never use, and the root pom manages every module in the repo. Reading it would inflate the
    # closure to "everything" and turn this gate into a source of false refusals.
    deps = project.findall("m:dependencies/m:dependency", NS) + project.findall(
        "m:profiles/m:profile/m:dependencies/m:dependency", NS)

    return {
        "path": pom_path,
        "dir": pom_path.parent,
        "rel": str(pom_path.parent.relative_to(root)) or ".",
        "groupId": group,
        "artifactId": artifact,
        "version": version,
        "packaging": text_of(project, "m:packaging") or "jar",
        "deps": [(text_of(d, "m:groupId"), text_of(d, "m:artifactId")) for d in deps],
    }


def key_of(module):
    return (module.get("groupId"), module.get("artifactId"))


def dependency_closure(by_key, start_key):
    seen, frontier = set(), [start_key]
    while frontier:
        current = frontier.pop()
        if current in seen or current not in by_key:
            continue
        seen.add(current)
        frontier.extend(by_key[current]["deps"])
    seen.discard(start_key)
    return seen


def newest_source(module):
    # src/main plus the module's own pom: a dependency or version bump warrants a rebuild exactly as a
    # source edit does. A module with no src/main is still CHECKED against its pom rather than
    # silently skipped -- a skip would be indistinguishable from a pass.
    candidates = [module["path"]]
    main = module["dir"] / "src" / "main"
    for dirpath, dirnames, filenames in os.walk(main):
        dirnames[:] = [d for d in dirnames if d not in PRUNE]
        candidates.extend(Path(dirpath) / name for name in filenames)

    newest_path, newest_mtime = None, -1.0
    for candidate in candidates:
        try:
            mtime = candidate.stat().st_mtime
        except OSError:
            continue
        if mtime > newest_mtime:
            newest_path, newest_mtime = candidate, mtime
    return newest_path, newest_mtime


def installed_jar(local_repo, module):
    group_path = Path(*module["groupId"].split("."))
    name = "%s-%s.jar" % (module["artifactId"], module["version"])
    return local_repo / group_path / module["artifactId"] / module["version"] / name


def resolve_local_repo(root, override):
    if override:
        return Path(override).expanduser(), "command line"

    config = root / ".mvn" / "maven.config"
    if config.is_file():
        for line in config.read_text().splitlines():
            line = line.strip()
            if line.startswith("-Dmaven.repo.local="):
                value = Path(line.split("=", 1)[1].strip()).expanduser()
                resolved = value if value.is_absolute() else (root / value)
                return resolved, str(config)

    return Path(os.environ.get("HOME", "~")).expanduser() / ".m2" / "repository", "default ~/.m2/repository"


def indeterminate(reason, detail):
    # The empty case is LOUD on purpose. A gate that examined nothing and exited green is the defect
    # this script exists to remove; it must not reproduce it one level up.
    print("  FRESHNESS CHECK EXAMINED ZERO MODULES.")
    print("  This result says NOTHING about whether the runtime is stale: %s" % reason)
    print("  %s" % detail)
    print("  Fix the condition above, or set FORGE_SKIP_FRESHNESS=1 to run the gate unverified.")
    return EXIT_INDETERMINATE


def main(argv):
    if not argv:
        print("usage: forge-freshness.py <repo-root> [<local-repo>]", file=sys.stderr)
        return EXIT_INDETERMINATE

    root = Path(argv[0]).resolve()
    local_repo, repo_source = resolve_local_repo(root, argv[1] if len(argv) > 1 else None)

    print("  local repository: %s  (from %s)" % (local_repo, repo_source))

    poms = [parse_pom(p, root) for p in discover_poms(root)]
    broken = [m for m in poms if m.get("broken")]
    modules = [m for m in poms if not m.get("broken") and m.get("artifactId") and m.get("groupId") and m.get("version")]
    by_key = {key_of(m): m for m in modules}

    forge = next((m for m in modules if m["rel"] == FORGE_TESTS), None)
    if forge is None:
        return indeterminate("the forge-tests module was not found, so its dependency closure is empty.",
                             "expected a pom at %s" % (root / FORGE_TESTS))

    closure = dependency_closure(by_key, key_of(forge))
    checkable = sorted((by_key[k] for k in closure if by_key[k]["packaging"] != "pom"),
                       key=lambda m: m["rel"])

    print("  scanned %d pom(s); forge-tests' intra-repo dependency closure is %d module(s), "
          "%d of them jar modules" % (len(poms), len(closure), len(checkable)))
    if broken:
        print("  WARNING: %d pom(s) unreadable and therefore NOT part of the closure: %s"
              % (len(broken), ", ".join(str(m["path"]) for m in broken[:5])))

    if not local_repo.is_dir():
        return indeterminate("the local repository directory does not exist, so no artifact could be read.",
                             "looked in %s (%s)" % (local_repo, repo_source))
    if not checkable:
        return indeterminate("the dependency closure resolved to no jar modules.",
                             "scanned %d pom(s) under %s" % (len(poms), root))

    fresh, stale, missing = [], [], []
    for module in checkable:
        jar = installed_jar(local_repo, module)
        source_path, source_mtime = newest_source(module)
        if not jar.is_file():
            missing.append((module, jar))
            continue
        jar_mtime = jar.stat().st_mtime
        record = (module, jar, jar_mtime, source_path, source_mtime)
        (stale if jar_mtime < source_mtime else fresh).append(record)

    examined = len(fresh) + len(stale)
    print("  examined %d module(s): %d fresh, %d stale, %d with no installed artifact"
          % (examined, len(fresh), len(stale), len(missing)))

    if examined == 0:
        return indeterminate("every module in the closure is missing its installed artifact.",
                             "%d module(s) had no jar under %s -- run ./build.sh" % (len(missing), local_repo))

    # Ticket option (c): state the artifact timestamps the run actually exercises, so a reader can see
    # what was executed instead of inferring it from a verdict.
    by_age = sorted(fresh + stale, key=lambda r: r[2])
    print("  installed artifacts exercised: oldest %s %s, newest %s %s"
          % (by_age[0][0]["rel"], stamp(by_age[0][2]), by_age[-1][0]["rel"], stamp(by_age[-1][2])))

    if missing:
        print("  NOT INSTALLED (absent, therefore unchecked -- not evidence of freshness):")
        for module, jar in missing:
            print("    %s -- no %s" % (module["rel"], jar.name))

    if stale:
        print()
        print("  STALE -- source is newer than the artifact this run would resolve:")
        for module, jar, jar_mtime, source_path, source_mtime in sorted(stale, key=lambda r: r[0]["rel"]):
            print("    %s" % module["rel"])
            print("      installed %s  %s" % (jar.name, stamp(jar_mtime)))
            print("      newest source %s  %s" % (source_path.relative_to(root), stamp(source_mtime)))
        return EXIT_STALE

    return EXIT_FRESH


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
