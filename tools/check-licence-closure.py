#!/usr/bin/env python3
"""Licence-closure gate (#1989), the sibling of check-publish-closure.py.

    python3 tools/check-licence-closure.py [--root DIR]

The BSL modules are the paths in tools/license/bsl-modules.txt; everything else is Apache-2.0. Three checks, exit 1 on any violation:
  (a) NO APACHE MODULE DEPENDS ON A BSL MODULE: not by a compile/runtime/provided dependency, not through its parent pom, and not by any
      edge (test scope included) from a PUBLISHED module. Every pom in the tree is examined, not only the default reactor (forge-tests sits
      behind a profile), and so are dependencies declared inside a <profile>. A test-scope edge from an unpublished module (skipPublishing, e.g. dead-surface-gate) is reported as info only.
  (b) EVERY HEADER MATCHES ITS MODULE: a .java file in a BSL module starts with `// SPDX-License-Identifier: BUSL-1.1`; no file outside
      them carries it (.java, .sh and .md alike, apart from files that discuss the licence).
  (c) THE LIST AND THE LICENSE MAP AGREE: the root LICENSE names exactly the listed paths; each BSL module has a LICENSE holding the BSL text
      and a pom declaring the BSL licence; no other pom declares it.
An empty module set is refused, not reported as clean (the check-publish-closure rule)."""
import importlib.util
import os
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("publish_closure", HERE / "check-publish-closure.py")
pc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pc)

STRICT = ("compile", "runtime", "provided")
SKIP_DIRS = {"target", ".m2-local", ".git", ".claude", "node_modules"}
BUSL = "SPDX-License-Identifier: BUSL-1.1"
EXEMPT = ("LICENSE", "CHANGELOG.md", "CLAUDE.md", "CONTRIBUTING.md", "docs/legal/", "tools/license/", "changelog.d/")


def walk(root):
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            yield (Path(base) / f).relative_to(root).as_posix()


def bsl_list(root):
    lines = (root / "tools/license/bsl-modules.txt").read_text().splitlines()
    return [l.strip().rstrip("/") for l in lines if l.strip() and not l.lstrip().startswith("#")]


def license_map(root):
    """Paths the root LICENSE lists under its Business Source License 1.1 heading (indented `dir/` lines, up to the parameters block)."""
    paths, active = [], False
    for line in (root / "LICENSE").read_text().splitlines():
        if line.startswith("Business Source License 1.1"):
            active = True
        elif line.startswith("BSL 1.1 parameters"):
            break
        elif active and (m := re.match(r"^\s{4}([A-Za-z0-9_.\-]+(?:/[A-Za-z0-9_.\-]+)*)/?(?:\s|$)", line)):
            paths.append(m.group(1))
    return paths


def under(path, entry):
    return path == entry or path.startswith(entry + "/")


def all_dependencies(module):
    """(group, artifact, declared scope, optional) for each <dependencies> entry, profile-scoped ones included."""
    yield from module.dependencies()
    for dependency in module.root.findall("m:profiles/m:profile/m:dependencies/m:dependency", pc.NS):
        yield (module.resolve(pc.text(dependency, "m:groupId") or ""), pc.text(dependency, "m:artifactId"),
               pc.text(dependency, "m:scope"), pc.text(dependency, "m:optional"))


def declares_bsl(pom):
    return any((pc.text(l, "m:name") or "").startswith("Business Source License") for l in pc.ET.parse(pom).getroot().findall("m:licenses/m:license", pc.NS))


def analyse(root):
    root = Path(root).resolve()
    bsl = bsl_list(root)
    is_bsl = lambda rel: any(under(rel, e) for e in bsl)
    files = list(walk(root))
    poms = {(root / f).resolve(): pc.Module(root / f) for f in files if f.endswith("pom.xml") and f.rsplit("/", 1)[-1] == "pom.xml"
            and "/src/" not in f}
    found, info, edges, java = [], [], 0, 0
    if not poms or not bsl:
        return ["examined no module (or an empty BSL list); refusing to report a closed set"], [], 0, 0, 0
    rel = lambda pom: Path(pom).parent.relative_to(root).as_posix()
    # (a)
    bsl_coordinates = {m.coordinate() for pom, m in poms.items() if is_bsl(rel(pom))}
    for pom, module in poms.items():
        if is_bsl(rel(pom)):
            continue
        parent = pc.parent_of(module, poms)
        if parent is not None and is_bsl(rel(parent.pom)):
            found.append(f"(a) {rel(pom) or '.'} (Apache) has a BSL parent pom {rel(parent.pom)}")
        for group, artifact, declared_scope, _optional in all_dependencies(module):
            if (group, artifact) not in bsl_coordinates:
                continue
            managed_scope, _ = pc.managed_entry(module, poms, (group, artifact))
            scope = declared_scope or managed_scope or "compile"
            edges += 1
            line = f"(a) {rel(pom)} (Apache) depends ({scope}) on {artifact} (BSL)"
            if scope in STRICT or pc.publishes(module, poms):
                found.append(line)
            else:
                info.append(line + "   [test scope, module not published, allowed]")
    # (b)
    for f in files:
        if not f.endswith((".java", ".sh", ".md")):
            continue
        head = (root / f).read_text(errors="ignore")[:1000]
        if f.endswith(".java"):
            java += 1
            if is_bsl(f) and not head.startswith("// " + BUSL):
                found.append(f"(b) {f}: in a BSL module but does not start with the BUSL-1.1 header")
        if not is_bsl(f) and BUSL in head and not f.startswith(EXEMPT):
            found.append(f"(b) {f}: outside the BSL modules but carries the BUSL-1.1 header")
    # (c)
    mapped = license_map(root)
    for path in sorted(set(bsl) ^ set(mapped)):
        found.append(f"(c) {path}: in {'bsl-modules.txt' if path in bsl else 'the LICENSE map'} only")
    if len(mapped) != len(set(mapped)):
        found.append("(c) the LICENSE map lists a path twice")
    for e in bsl:
        lic = root / e / "LICENSE"
        if not (root / e).is_dir():
            found.append(f"(c) {e}: listed as BSL but is not a directory")
        elif not lic.is_file() or "Business Source License 1.1" not in lic.read_text():
            found.append(f"(c) {e}/LICENSE: missing or not the BSL text")
        if (root / e / "pom.xml").is_file() and not declares_bsl(root / e / "pom.xml"):
            found.append(f"(c) {e}/pom.xml: a BSL module whose pom does not declare the BSL licence")
    for pom in poms:
        if not is_bsl(rel(pom)) and declares_bsl(pom):
            found.append(f"(c) {rel(pom) or '.'}/pom.xml: declares the BSL licence outside the BSL modules")
    for f in files:
        if f.endswith("/LICENSE") and not is_bsl(f) and "Business Source License 1.1" in (root / f).read_text(errors="ignore"):
            found.append(f"(c) {f}: a BSL LICENSE outside the BSL modules")
    return found, info, len(poms), edges, java


def main():
    args = sys.argv[1:]
    root = Path(args[args.index("--root") + 1]) if "--root" in args else HERE.parent
    found, info, modules, edges, java = analyse(root)
    for line in found + info:
        print(line, file=sys.stderr)
    print(f"examined {modules} pom(s), {edges} Apache->BSL edge(s), {java} .java file(s): {len(found)} violation(s), {len(info)} test-scope note(s)")
    return 1 if found or not modules else 0


if __name__ == "__main__":
    sys.exit(main())
