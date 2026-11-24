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
  - the Testcontainers version must be one value across the poms, and the list pins that version's Ryuk image and, for the modules
    that trigger it, its TINY_IMAGE (`alpine:<tag>`, started once per JVM when a container carries a bind). Neither tag is read from
    the library: like Ryuk's, it is retagged by hand when <testcontainers.version> moves, and the started-image comparison below
    is what goes red when the tag drifts.

List format, one entry per line: `<image> <module>[,<module>...]  # why`. A module suffixed `!` is referenced but its
tests are excluded from the required jobs (a heavy-db or S3 group), so it is inventoried and never pre-pulled for. The
Ryuk entry's module is `*` (every test group).

Source shapes are a pre-check only (#3646): a new way of naming an image is a silent miss. The claim rests on the second mode,
which compares what a test-group job STARTED with the list (tools/ci-record-started-images.sh records every container create
the Docker daemon sees; the image-name substitutor is configured by environment, so a daemon-side record needs no test dependency):

  - an image started in the group that the list does not name for the group's modules fails by name;
  - an image started in a group is covered only when the group's pre-pull pulls it: a row with the `!` (excluded) never does, so a
    start of an excluded image fails by name; one table (VERDICT) maps (row state, started?) to the verdict;
  - a group whose pre-pull pulls a non-Ryuk image but whose record is empty fails: a dead recorder reads as "nothing started".
    A group whose modules start nothing and list only excluded rows (g1) expects nothing and is green;
  - names are compared through canonical(): mirror prefix, Docker Hub host, `library/` and an implicit :latest are one spelling.

  check-ci-test-images.py [--root DIR]                          exit 0 consistent, 1 drift, 2 cannot read
  check-ci-test-images.py --started FILE --plan MODULES_FILE    the started-image comparison for one test group
                          [--prefix HUB_PREFIX]
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
# A test base class asks its subclasses for the image (`String image() { return "redis:7"; }`, #3646): the subclass file need not
# import Testcontainers, so this shape is read from test sources of any module that starts a container elsewhere.
METHOD_RETURN = re.compile(r'\bString\s+\w*[iI]mage\w*\s*\(\s*\)\s*\{\s*return\s+"([^"]+)"\s*;')
QUARANTINE = re.compile(r'@Tag\(\s*"quarantine"')
RYUK = "testcontainers/ryuk:"
# DockerClientFactory.TINY_IMAGE (Testcontainers 1.21.4, DockerClientFactory.java:82): the library starts it once per JVM, for the
# file-mounting probe, when a container carries a bind (a test group whose module uses LocalStackContainer, which binds docker.sock).
TINY = "alpine:"
LIBRARY_DEFAULTS = (RYUK, TINY)
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


def is_test_source(rel):
    parts = rel.parts
    return "src" in parts and parts[parts.index("src") + 1:parts.index("src") + 2] == ("test",)


def referenced(root):
    """{image: {module}} for every image literal in a source that imports Testcontainers."""
    found = {}
    container_modules = set()
    for path in java_sources(root):
        text = path.read_text(encoding="utf-8", errors="replace")
        if "org.testcontainers" not in text:
            continue
        module = module_of(path.relative_to(root))
        container_modules.add(module)
        for shape in SHAPES:
            for image in shape.findall(text):
                found.setdefault(image, set()).add(module)
    for path in java_sources(root):
        rel = path.relative_to(root)
        module = module_of(rel)
        if module in container_modules and is_test_source(rel):
            for image in METHOD_RETURN.findall(path.read_text(encoding="utf-8", errors="replace")):
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
    for image in sorted(k for k in entries.keys() - found.keys() if not k.startswith(LIBRARY_DEFAULTS)):
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
    tiny = [i for i in entries if i.startswith(TINY) and i not in found]
    if len(tiny) > 1:
        errors.append(f"expected at most one library-default {TINY}<tag> entry (Testcontainers' TINY_IMAGE), found {tiny}")
    for source in quarantined_container_tests(root):
        errors.append(f"QUARANTINED container test {source}: the quarantine job does not pre-pull images; "
                      f"add a pre-pull step there before tagging a Testcontainers test quarantine")
    return errors, len(found), len(entries), versions


HUB_HOSTS = ("docker.io/", "index.docker.io/", "registry-1.docker.io/")
IMAGE_ID = re.compile(r"^(?:sha256:)?[0-9a-f]{12}(?:[0-9a-f]{52})?$")


def canonical(image, prefix=""):
    """The one spelling of an image name, for the list's rows and the daemon's records alike (#3646 F3).

    Removed: the registry-mirror prefix, a Docker Hub host (docker.io, index.docker.io, registry-1.docker.io) and the official-image
    `library/`; an absent tag becomes `:latest`. Kept: a digest (the list pins minio by digest) and every other registry host."""
    image = image.strip()
    if IMAGE_ID.match(image):
        return image
    if prefix and image.startswith(prefix):
        image = image[len(prefix):]
    for host in HUB_HOSTS:
        if image.startswith(host):
            image = image[len(host):]
            break
    if image.startswith("library/") and image.count("/") == 1:
        image = image[len("library/"):]
    name = image.split("@", 1)[0]
    if "@" not in image and ":" not in name.rsplit("/", 1)[-1]:
        image += ":latest"
    return image


def started_images(text, prefix):
    """The set of image names in a recorded `docker events` file, one per line, each through canonical()."""
    return {canonical(line, prefix) for line in text.splitlines() if line.strip()}


def lookup(index, image):
    """The list key for a recorded canonical name: exact, else the same name without a digest where the list pins none."""
    if image in index:
        return index[image]
    bare = image.split("@", 1)[0]
    return index.get(bare) if "@" in image else None


# One image row, seen from one test group's plan (what prepull's wanted() selects is LISTED):
#   LISTED    a row names `*` or a non-`!` module of the plan: the pre-pull pulls the image for this group
#   EXCLUDED  every row naming a plan module has the `!`: inventoried, never pre-pulled
#   ELSEWHERE rows exist, none names a plan module
#   BY_ID     the daemon recorded an image ID, not a name: it cannot be matched to a row
#   ABSENT    no row
LISTED, EXCLUDED, ELSEWHERE, BY_ID, ABSENT = "listed", "excluded", "elsewhere", "by_id", "absent"
# (state, started in this group) -> verdict; None is green. Every cell is named: a started image is covered only when LISTED.
VERDICT = {
    (LISTED, True): None, (LISTED, False): None,
    (EXCLUDED, True): "STARTED image {image} is listed only as excluded (`!`) for {modules}: the pre-pull never pulls it, "
                      "so this start is not covered; drop the `!` in {list} if its tests belong in the required jobs, else find why this group ran them",
    (EXCLUDED, False): None,
    (ELSEWHERE, True): "STARTED image {image} is listed for {modules}, none in this group's modules: "
                       "add the starting module to its {list} entry",
    (ELSEWHERE, False): None,
    (BY_ID, True): "STARTED a container from image ID {image}: an ID cannot be matched to {list}; start the image by name",
    (BY_ID, False): None,
    (ABSENT, True): "STARTED image {image} is not in {list}: add it with the module that starts it",
    (ABSENT, False): None,
}


def row_state(modules, plan):
    if modules is None:
        return ABSENT
    if "*" in modules or any(m in plan for m in modules if not m.endswith("!")):
        return LISTED
    if any(m[:-1] in plan for m in modules if m.endswith("!")):
        return EXCLUDED
    return ELSEWHERE


def expected_images(entries, plan):
    """The images this group's pre-pull pulls for its own modules (not the `*` Ryuk row): a group that lists one starts a container."""
    return sorted(i for i, m in entries.items() if "*" not in m and any(x in plan for x in m if not x.endswith("!")))


def started_problems(root, started, plan):
    """Errors for a test group (`plan` = its modules) by VERDICT, plus NOTHING RECORDED when an expected image has no record at all."""
    entries = listed(root)
    index = {canonical(image): image for image in entries}
    errors = []
    for image in sorted(index.keys() | started):
        key = lookup(index, image)
        if IMAGE_ID.match(image):
            state = BY_ID
        else:
            state = row_state(entries.get(key) if key else None, plan)
        template = VERDICT[(state, image in started)]
        if template:
            errors.append(template.format(image=image, modules=sorted(entries.get(key, ())), list=LIST))
    expected = expected_images(entries, plan)
    if expected and not started:
        errors.append(f"NOTHING RECORDED although this group's modules start containers (e.g. {expected[0]}): "
                      f"the recorder was not running, so this run proves nothing")
    return errors


def main_started(args):
    try:
        plan = {line.strip() for line in args.plan.read_text(encoding="utf-8").splitlines() if line.strip()}
        started = started_images(args.started.read_text(encoding="utf-8"), args.prefix)
        errors = started_problems(args.root, started, plan)
    except (OSError, ValueError) as error:
        print(f"::error::cannot compare the started images: {error}")
        return 2
    for error in errors:
        print(f"::error::{error}")
    expected = expected_images(listed(args.root), plan)
    print(f"ci-test-images: {len(started)} image(s) started in this group: {', '.join(sorted(started)) or 'none'}; "
          f"{'expected ' + str(len(expected)) + ' listed image(s)' if expected else 'nothing expected (no non-excluded row for this group)'}; "
          f"{len(errors)} problem(s)")
    return 1 if errors else 0


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--started", type=Path, help="recorded started images, one per line (compares them with the list)")
    parser.add_argument("--plan", type=Path, help="the test group's modules, one per line (with --started)")
    parser.add_argument("--prefix", default="", help="registry-mirror prefix to strip from recorded images (with --started)")
    return parser


def main():
    parser = build_parser()
    args = parser.parse_args()
    if args.started or args.plan:
        if not (args.started and args.plan):
            parser.error("--started and --plan go together")
        return main_started(args)
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
