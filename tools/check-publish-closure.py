#!/usr/bin/env python3
"""Refuse a release whose published artifact set is not closed under its own dependencies (#1219, #1211).

Walks the reactor from the root pom's <modules> (default build, no profiles), and for every module that PUBLISHES
(the Central publishing plugin's `skipPublishing` is not true, set in the module's <build><plugins> or inherited through
its parent chain) requires that:
  - every `org.pragmatica-lite*` dependency that is itself a reactor module, at compile or runtime scope and not
    optional, also publishes;
  - its reactor parent pom also publishes (a consumer resolves the parent to read the child).
A published module that depends on an unpublished one is unusable from Central: its consumers get a hard
resolution failure, while every local build resolves the dependency from a populated local repository and passes.

`maven.deploy.skip` is deliberately NOT read: the Central publishing plugin ignores it. `pg-test-corpus` sets it and is
on Central at 1.0.0-rc3 regardless; `slice-testkit` sets `skipPublishing` and is not.

Prints the number of published modules and dependency edges it examined; exits 1 with one line per violation.
"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
GROUP_PREFIX = "org.pragmatica-lite"
PUBLISHING_PLUGIN = "central-publishing-maven-plugin"


def text(element, path):
    found = element.find(path, NS)
    return found.text.strip() if found is not None and found.text else None


class Module:
    def __init__(self, pom):
        self.pom = pom
        self.root = ET.parse(pom).getroot()
        self.parent_pom = None
        parent = self.root.find("m:parent", NS)
        if parent is not None:
            relative = text(parent, "m:relativePath")
            candidate = (pom.parent / (relative if relative is not None else "..")).resolve()
            candidate = candidate / "pom.xml" if candidate.is_dir() else candidate
            if relative != "" and candidate.is_file():
                self.parent_pom = candidate
        self.artifact_id = text(self.root, "m:artifactId")
        self.own_group = text(self.root, "m:groupId")
        self.parent_group = text(self.root, "m:parent/m:groupId")

    @property
    def group_id(self):
        return self.own_group or self.parent_group

    def coordinate(self):
        return (self.group_id, self.artifact_id)

    def skip_publishing(self):
        """True/False when this pom's <build><plugins> configures the publishing plugin's skip; None otherwise."""
        for plugin in self.root.findall("m:build/m:plugins/m:plugin", NS):
            if text(plugin, "m:artifactId") == PUBLISHING_PLUGIN:
                value = text(plugin, "m:configuration/m:skipPublishing")
                if value is not None:
                    return value == "true"
        return None

    def dependencies(self):
        for dependency in self.root.findall("m:dependencies/m:dependency", NS):
            group = text(dependency, "m:groupId") or ""
            group = group.replace("${project.groupId}", self.group_id or "")
            scope = text(dependency, "m:scope") or "compile"
            optional = text(dependency, "m:optional") == "true"
            yield group, text(dependency, "m:artifactId"), scope, optional


def reactor(root_pom):
    modules = {}
    pending = [root_pom.resolve()]
    while pending:
        pom = pending.pop()
        if pom in modules:
            continue
        module = Module(pom)
        modules[pom] = module
        for name in module.root.findall("m:modules/m:module", NS):
            child = (pom.parent / name.text.strip()).resolve()
            pending.append(child / "pom.xml" if child.is_dir() else child)
    return modules


def publishes(module, modules):
    """The nearest explicit `skipPublishing` wins, walking up the parent chain; the default is to publish."""
    current = module
    while current is not None:
        skip = current.skip_publishing()
        if skip is not None:
            return not skip
        current = modules.get(current.parent_pom) if current.parent_pom else None
    return True


def violations(root_pom):
    modules = reactor(root_pom)
    by_coordinate = {module.coordinate(): module for module in modules.values()}
    published = {pom: module for pom, module in modules.items() if publishes(module, modules)}
    found = []
    edges = 0
    for module in published.values():
        parent = modules.get(module.parent_pom) if module.parent_pom else None
        if parent is not None and parent.pom not in published:
            found.append(f"{module.artifact_id}: its parent {parent.artifact_id} ({parent.pom}) is not published")
        for group, artifact, scope, optional in module.dependencies():
            if not group.startswith(GROUP_PREFIX) or optional or scope not in ("compile", "runtime"):
                continue
            target = by_coordinate.get((group, artifact))
            if target is None:
                continue
            edges += 1
            if target.pom not in published:
                found.append(f"{module.artifact_id} ({scope}) depends on {artifact}, which is not published ({target.pom})")
    return len(published), edges, found


def main():
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "pom.xml"
    count, edges, found = violations(root)
    if count == 0:
        print("examined no published module; refusing to report a closed set", file=sys.stderr)
        return 1
    for line in found:
        print("NOT CLOSED: " + line, file=sys.stderr)
    print(f"examined {count} published module(s) and {edges} in-reactor dependency edge(s): {len(found)} violation(s)")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
