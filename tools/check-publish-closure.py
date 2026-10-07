#!/usr/bin/env python3
"""Refuse a release whose published artifact set is not closed under its own dependencies (#1219, #1211).

Walks the reactor from the root pom's <modules> (default build, no profiles), and for every module that PUBLISHES
(the Central publishing plugin is bound for it and its `skipPublishing` is not true) requires that:
  - every dependency that is itself a reactor module, at compile or runtime scope (the declared scope, else the
    managed one) and not optional, also publishes;
  - its reactor parent pom also publishes (a consumer resolves the parent to read the child).
A published module that depends on an unpublished one is unusable from Central: its consumers get a hard
resolution failure, while every local build resolves the dependency from a populated local repository and passes.

A module publishes only by OPT-IN (#1988, #1989). The root skips by default (`skipPublishing=true` in its
<pluginManagement>, and `maven.deploy.skip=true` for a module the plugin is not bound to), so a new module stays
unpublished until someone declares the plugin in its own <build><plugins> with `<skipPublishing>false</skipPublishing>`.
The plugin entry follows Maven's inheritance: a child inherits its parent's entry, and an entry with
`<inherited>false</inherited>` is not passed on, which is how a parent pom publishes without publishing every child.

`maven.deploy.skip` is deliberately NOT read: the Central publishing plugin ignores it. `pg-test-corpus` set it and is
on Central at 1.0.0-rc3 regardless; `slice-testkit` sets `skipPublishing` and is not.

`--table` prints the per-module decision table (module, coordinate, packaging, decision) for the release docs;
`--check-table FILE` fails when FILE's decisions differ from the poms (a module missing, extra, or decided otherwise).

Prints the number of published modules and dependency edges it examined; exits 1 with one line per violation.
"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
PUBLISHING_PLUGIN = "central-publishing-maven-plugin"


def text(element, path):
    found = element.find(path, NS)
    return found.text.strip() if found is not None and found.text else None


def declared_artifact(pom):
    return text(ET.parse(pom).getroot(), "m:artifactId")


class Module:
    def __init__(self, pom):
        self.pom = pom
        self.root = ET.parse(pom).getroot()
        self.parent_pom = None
        parent = self.root.find("m:parent", NS)
        if parent is not None:
            # An EMPTY <relativePath/> disables the local lookup (Maven resolves the parent from the repository);
            # an absent one means "..". A local candidate counts only if it IS the declared parent (#1707 review).
            relative_element = parent.find("m:relativePath", NS)
            if relative_element is None or (relative_element.text or "").strip():
                relative = relative_element.text.strip() if relative_element is not None else ".."
                candidate = (pom.parent / relative).resolve()
                candidate = candidate / "pom.xml" if candidate.is_dir() else candidate
                if candidate.is_file() and declared_artifact(candidate) == text(parent, "m:artifactId"):
                    self.parent_pom = candidate
        self.artifact_id = text(self.root, "m:artifactId")
        self.own_group = text(self.root, "m:groupId")
        self.parent_group = text(self.root, "m:parent/m:groupId")

    @property
    def group_id(self):
        return self.own_group or self.parent_group

    def coordinate(self):
        return (self.group_id, self.artifact_id)

    def plugin_entry(self):
        """The publishing plugin's own <build><plugins> entry as (skipPublishing or None, inherited or None); None if absent.

        `skipPublishing` is True/False when configured; `inherited` is True/False when the entry says so."""
        for plugin in self.root.findall("m:build/m:plugins/m:plugin", NS):
            if text(plugin, "m:artifactId") == PUBLISHING_PLUGIN:
                value = text(plugin, "m:configuration/m:skipPublishing")
                inherited = text(plugin, "m:inherited")
                return (None if value is None else value == "true", None if inherited is None else inherited == "true")
        return None

    def managed_skip(self):
        """True/False when this pom's <pluginManagement> configures the plugin's skip (the default for a bound module)."""
        for plugin in self.root.findall("m:build/m:pluginManagement/m:plugins/m:plugin", NS):
            if text(plugin, "m:artifactId") == PUBLISHING_PLUGIN:
                value = text(plugin, "m:configuration/m:skipPublishing")
                if value is not None:
                    return value == "true"
        return None

    def dependencies(self):
        """(group, artifact, declared scope or None, declared optional or None) for each <dependencies> entry."""
        for dependency in self.root.findall("m:dependencies/m:dependency", NS):
            yield (self.resolve(text(dependency, "m:groupId") or ""), text(dependency, "m:artifactId"),
                   text(dependency, "m:scope"), text(dependency, "m:optional"))

    def managed(self):
        """(group, artifact) -> (scope, optional) from this pom's own <dependencyManagement>."""
        entries = {}
        for dependency in self.root.findall("m:dependencyManagement/m:dependencies/m:dependency", NS):
            key = (self.resolve(text(dependency, "m:groupId") or ""), text(dependency, "m:artifactId"))
            entries[key] = (text(dependency, "m:scope"), text(dependency, "m:optional"))
        return entries

    def resolve(self, value):
        return value.replace("${project.groupId}", self.group_id or "")


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


def parent_of(module, modules):
    return modules.get(module.parent_pom) if module.parent_pom else None


def effective_entry(module, modules):
    """The publishing plugin entry Maven gives `module`: its own entry merged over what its parent passes on.

    A parent passes on its effective entry unless that entry is `inherited=false`. Merge: the child's explicit
    `skipPublishing` and `inherited` win; otherwise the parent's are kept. None means the plugin is not bound."""
    own = module.plugin_entry()
    parent = parent_of(module, modules)
    passed = effective_entry(parent, modules) if parent is not None else None
    if passed is not None and passed[1] is False:
        passed = None
    if own is None:
        return passed
    if passed is None:
        return own
    return (own[0] if own[0] is not None else passed[0], own[1] if own[1] is not None else passed[1])


def managed_default(module, modules):
    """The nearest <pluginManagement> `skipPublishing`, the module's own pom first, then up the parent chain."""
    current = module
    while current is not None:
        skip = current.managed_skip()
        if skip is not None:
            return skip
        current = parent_of(current, modules)
    return None


def publishes(module, modules):
    """Opt-in: published only when the plugin is bound for the module and its effective `skipPublishing` is false.

    No bound plugin means nothing publishes it (the root's `maven.deploy.skip=true` skips the default deploy). A bound
    plugin without an explicit value takes the <pluginManagement> default; with none anywhere the plugin's own default
    (publish) applies."""
    entry = effective_entry(module, modules)
    if entry is None:
        return False
    skip = entry[0] if entry[0] is not None else managed_default(module, modules)
    return skip is not True


def managed_entry(module, modules, key):
    """The nearest <dependencyManagement> entry for `key`, walking up the parent chain (#1707 review: an omitted
    scope takes the managed one, e.g. `test`)."""
    current = module
    while current is not None:
        entry = current.managed().get(key)
        if entry is not None:
            return entry
        current = modules.get(current.parent_pom) if current.parent_pom else None
    return None, None


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
        for group, artifact, declared_scope, declared_optional in module.dependencies():
            # Every reactor module counts, whatever its group (#1707 review); the coordinate lookup decides membership.
            target = by_coordinate.get((group, artifact))
            if target is None:
                continue
            managed_scope, managed_optional = managed_entry(module, modules, (group, artifact))
            scope = declared_scope or managed_scope or "compile"
            optional = (declared_optional or managed_optional) == "true"
            if optional or scope not in ("compile", "runtime"):
                continue
            edges += 1
            if target.pom not in published:
                found.append(f"{module.artifact_id} ({scope}) depends on {artifact}, which is not published ({target.pom})")
    return len(published), edges, found


def decisions(root_pom):
    """(relative path, groupId, artifactId, packaging, published) for every reactor module, sorted by path."""
    root = root_pom.resolve()
    modules = reactor(root)
    rows = []
    for pom, module in modules.items():
        packaging = text(module.root, "m:packaging") or "jar"
        relative = pom.parent.relative_to(root.parent).as_posix()
        rows.append((relative, module.group_id, module.artifact_id, packaging, publishes(module, modules)))
    return sorted(rows)


def table(root_pom):
    """The decision table for the release docs (the `reason` column is filled in by hand)."""
    lines = ["| Module | Coordinate | Packaging | Decision |", "|---|---|---|---|"]
    for relative, group, artifact, packaging, published in decisions(root_pom):
        lines.append(f"| `{relative}` | `{group}:{artifact}` | {packaging} | {'publish' if published else 'skip'} |")
    return lines


def documented(path):
    """module path -> decision, from the first two columns after the module path of the docs' table rows."""
    found = {}
    for line in Path(path).read_text().splitlines():
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if line.startswith("|") and len(cells) >= 4 and cells[0].startswith("`") and cells[3] in ("publish", "skip"):
            found[cells[0].strip("`")] = cells[3]
    return found


def table_violations(root_pom, doc):
    expected = {relative: "publish" if published else "skip" for relative, _, _, _, published in decisions(root_pom)}
    recorded = documented(doc)
    found = []
    for module, decision in expected.items():
        if module not in recorded:
            found.append(f"{module}: decided '{decision}' by the poms, absent from the decision table")
        elif recorded[module] != decision:
            found.append(f"{module}: the poms decide '{decision}', the table says '{recorded[module]}'")
    for module in recorded:
        if module not in expected:
            found.append(f"{module}: in the decision table, not a reactor module")
    return found


def main():
    arguments = sys.argv[1:]
    default_root = Path(__file__).resolve().parent.parent / "pom.xml"
    if arguments[:1] == ["--table"]:
        print("\n".join(table(default_root)))
        return 0
    if arguments[:1] == ["--check-table"] and len(arguments) == 2:
        found = table_violations(default_root, arguments[1])
        for line in found:
            print("TABLE DRIFT: " + line, file=sys.stderr)
        print(f"decision table {arguments[1]}: {len(found)} difference(s) from the poms")
        return 1 if found else 0
    root = Path(arguments[0]) if arguments else default_root
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
