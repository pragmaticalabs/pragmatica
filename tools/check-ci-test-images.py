#!/usr/bin/env python3
"""Testcontainers image inventory gate (#3124): tools/ci-test-images.txt names every container image the tests start.

A pull of an image from the registry mirror fails transiently, and a failed pull ejects a green PR from the merge queue.
tools/ci-prepull-images.sh pre-pulls the listed images with bounded retry before the test step; this check keeps that
list honest, in both directions:

  - an image a Java source starts (new <X>Container("img"), DockerImageName.parse("img"), a *_IMAGE / *_DIGEST constant)
    that the list does not name fails, so a new image cannot be silently missed;
  - a listed image no source starts, or one whose modules differ from the sources', fails, so the list never rots;
  - a Testcontainers test tagged quarantine fails: that job does not pre-pull (known limits: an image held in a constant
    not named *IMAGE*/*DIGEST*, DockerImageName.parse(variable), compose files and docker-CLI users are not seen);
  - a pulled module that no test-group owns (tools/ci-test-groups.txt) fails: the pre-pull step would never run for it;
  - the Testcontainers version must be one value across the poms, and the list pins that version's Ryuk image.

List format, one entry per line: `<image> <module>[,<module>...]  # why`. A module suffixed `!` is referenced but its
tests are excluded from the required jobs (a heavy-db or S3 group), so it is inventoried and never pre-pulled for. The
Ryuk entry's module is `*` (every test group).

  check-ci-test-images.py [--root DIR]     exit 0 consistent, 1 drift, 2 cannot read
"""
import argparse
import re
import sys
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parent.parent
LIST = "tools/ci-test-images.txt"
GROUPS = "tools/ci-test-groups.txt"
# A source starts an image through one of these three shapes; each captures the image literal.
SHAPES = (
    re.compile(r'new\s+\w*Container(?:<[^>]*>)?\(\s*"([^"]+)"'),
    re.compile(r'DockerImageName\.parse\(\s*"([^"]+)"'),
    re.compile(r'\b\w*(?:IMAGE|DIGEST)\w*\s*=\s*"([^"]+)"'),
    re.compile(r'\.withImage\(\s*"([^"]+)"'),
)
QUARANTINE = re.compile(r'@Tag\(\s*"quarantine"')
RYUK = "testcontainers/ryuk:"
TC_VERSION = re.compile(r"<testcontainers\.version>\s*([^<\s]+)\s*</testcontainers\.version>")
IGNORED_DIRS = {"target", ".git", "node_modules", ".m2-local"}


def java_sources(root):
    for path in root.rglob("*.java"):
        if not IGNORED_DIRS.intersection(path.relative_to(root).parts):
            yield path


def module_of(rel):
    parts = rel.parts
    for src in ("src",):
        if src in parts:
            return "/".join(parts[:parts.index(src)])
    return "/".join(parts[:-1])


def referenced(root):
    """{image: {module}} for every image literal in a source that imports Testcontainers."""
    found = {}
    for path in java_sources(root):
        text = path.read_text(encoding="utf-8", errors="replace")
        if "org.testcontainers" not in text:
            continue
        module = module_of(path.relative_to(root))
        for shape in SHAPES:
            for image in shape.findall(text):
                found.setdefault(image, set()).add(module)
    return found


def quarantined_container_tests(root):
    """Sources that import Testcontainers and carry @Tag("quarantine"): the quarantine job does not pre-pull."""
    found = []
    for path in java_sources(root):
        text = path.read_text(encoding="utf-8", errors="replace")
        if "org.testcontainers" in text and QUARANTINE.search(text):
            found.append(str(path.relative_to(root)))
    return sorted(found)


def listed(root):
    """{image: {module}} from the list file; raises ValueError on a malformed line."""
    entries = {}
    for number, raw in enumerate((root / LIST).read_text(encoding="utf-8").splitlines(), 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        fields = line.split()
        if len(fields) != 2:
            raise ValueError(f"{LIST}:{number}: expected `<image> <modules>`, got: {raw!r}")
        if fields[0] in entries:
            raise ValueError(f"{LIST}:{number}: {fields[0]} listed twice")
        entries[fields[0]] = set(fields[1].split(","))
    return entries


def test_group_modules(root):
    owned = set()
    for raw in (root / GROUPS).read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].split()
        if len(line) == 2 and not line[0].startswith("job:"):
            owned.add(line[1])
    return owned


def testcontainers_versions(root):
    versions = set()
    for pom in root.rglob("pom.xml"):
        if not IGNORED_DIRS.intersection(pom.relative_to(root).parts):
            versions.update(TC_VERSION.findall(pom.read_text(encoding="utf-8", errors="replace")))
    return versions


def problems(root):
    found = referenced(root)
    entries = listed(root)
    owned = test_group_modules(root)
    errors = []
    for image in sorted(found.keys() - entries.keys()):
        errors.append(f"UNLISTED image {image} (started in {', '.join(sorted(found[image]))}): add it to {LIST}")
    for image in sorted(k for k in entries.keys() - found.keys() if not k.startswith(RYUK)):
        errors.append(f"STALE entry {image}: no source starts it; remove it from {LIST}")
    for image in sorted(found.keys() & entries.keys()):
        modules = {m.rstrip("!") for m in entries[image]}
        if modules != found[image]:
            errors.append(f"MODULES differ for {image}: list {sorted(modules)}, sources {sorted(found[image])}")
    for image, modules in sorted(entries.items()):
        for module in sorted(m for m in modules if m != "*" and not m.endswith("!") and m not in owned):
            errors.append(f"NO TEST GROUP owns {module} ({image}): the pre-pull step never runs for it")
    versions = testcontainers_versions(root)
    ryuk = [i for i in entries if i.startswith(RYUK)]
    if len(versions) != 1:
        errors.append(f"Testcontainers version is not a single value across the poms: {sorted(versions)}")
    if len(ryuk) != 1:
        errors.append(f"expected exactly one {RYUK}<tag> entry, found {ryuk}")
    for source in quarantined_container_tests(root):
        errors.append(f"QUARANTINED container test {source}: the quarantine job does not pre-pull images; "
                      f"add a pre-pull step there before tagging a Testcontainers test quarantine")
    return errors, len(found), len(entries), versions


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    args = parser.parse_args()
    try:
        errors, referenced_count, listed_count, versions = problems(args.root)
    except (OSError, ValueError) as error:
        print(f"::error::cannot check the Testcontainers image list: {error}")
        return 2
    for error in errors:
        print(f"::error::{error}")
    print(f"ci-test-images: {referenced_count} image(s) referenced by sources, {listed_count} listed, "
          f"testcontainers {sorted(versions)}, {len(errors)} problem(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
