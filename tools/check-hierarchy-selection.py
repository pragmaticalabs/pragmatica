#!/usr/bin/env python3
"""Refuse partial hierarchy acceptance selections before and after Forge execution.

A selection is complete when it names every `Hierarch*Test` class present in the Forge test directory plus
the supporting classes in SUPPORTING_CLASSES (#1451: before, a selection of 1 of 24 classes was verified and
exited 0, because only the NAMED classes were checked). Before the build, every required class must be
selected and every selected class must exist; after Forge, every selected class must report executed,
passing, non-skipped cases."""
import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

TEST_DIRECTORY = Path("aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge")
TEST_PACKAGE = "org.pragmatica.aether.forge."
# Non-hierarchy classes the acceptance run must include; the Hierarch*Test classes are discovered on disk.
SUPPORTING_CLASSES = ("EmberAddNodeRoleLabelTest", "ClusterFormationTest", "StreamOwnershipDriverFenceTest",
                      "SliceInvocationTest", "DeclarativeStreamConsumerTest", "DurableProjectionRebuildForgeTest")


def selected_classes(selector):
    names = selector.split(",")
    if not names or any(not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", name) for name in names):
        raise ValueError("Acceptance selection must contain explicit class names, without patterns or methods")
    if len(set(names)) != len(names):
        raise ValueError("Acceptance selection contains duplicate classes")
    return names


def verify_sources(root, names):
    missing = [name for name in names if not (root / TEST_DIRECTORY / (name + ".java")).is_file()]
    if missing:
        raise ValueError("Selected test classes are absent: " + ", ".join(missing))


def required_classes(root):
    hierarchy = sorted(path.stem for path in (root / TEST_DIRECTORY).glob("Hierarch*Test.java"))
    if not hierarchy:
        raise ValueError("Found no Hierarch*Test classes under " + str(root / TEST_DIRECTORY)
                         + "; refusing to judge a selection against an empty expected set")
    return hierarchy + list(SUPPORTING_CLASSES)


def verify_complete(root, names):
    required = required_classes(root)
    missing = [name for name in required if name not in names]
    if missing:
        raise ValueError(f"Partial selection: {len(missing)} of {len(required)} required classes are not selected: "
                         + ", ".join(missing))
    return required


def verify_reports(reports, names):
    executed = set()
    unsuccessful = set()
    for report in reports.glob("TEST-*.xml"):
        for case in ET.parse(report).iter("testcase"):
            classname = case.get("classname", "")
            outer = classname.split("$", 1)[0]
            if not outer.startswith(TEST_PACKAGE):
                continue
            name = outer[len(TEST_PACKAGE):]
            if name not in names:
                continue
            if any(case.find(tag) is not None for tag in ("skipped", "failure", "error")):
                unsuccessful.add(name)
            else:
                executed.add(name)
    missing = sorted(set(names) - executed)
    if missing or unsuccessful:
        raise ValueError("Incomplete acceptance reports; no passing cases: " + ", ".join(missing)
                         + "; failed/error/skipped classes: " + ", ".join(sorted(unsuccessful)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("selector")
    parser.add_argument("--reports", type=Path)
    args = parser.parse_args()
    try:
        names = selected_classes(args.selector)
        root = Path(__file__).resolve().parent.parent
        verify_sources(root, names)
        required = verify_complete(root, names)
        if args.reports is not None:
            verify_reports(args.reports, names)
    except (ValueError, OSError, ET.ParseError) as error:
        print(str(error), file=sys.stderr)
        return 1
    print(f"Verified {len(names)} selected hierarchy classes (all {len(required)} required classes selected)"
          + (" and their executed cases" if args.reports is not None else " before build"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
