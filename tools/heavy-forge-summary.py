#!/usr/bin/env python3
"""Summarise the Heavy forge failsafe reports (heavy-forge.yml), and say whether the night is OK, RED or UNKNOWN.

    python3 tools/heavy-forge-summary.py [REPORT_DIR]      # default aether/forge/forge-tests/target/failsafe-reports

Prints the markdown summary on stdout (the workflow tees it into the step summary) and, when GITHUB_OUTPUT is set, writes the outputs
`state` (ok | red | unknown), `reason`, `new_reds` and `known_reds` for the `notify` job.

UNKNOWN is never green: no report files, a report that does not parse, or zero testcases means the run examined nothing, which says
nothing about the product. It exits 1 on UNKNOWN so the step (and, with pipefail, the pipeline) fails. A new red (failure or error) is `red`;
a known red (`@KnownRed`, a skipped testcase whose text starts "known red #N") is listed apart and does not make the night red.
Counts `<testcase>` elements, never the `<testsuite tests="N">` attribute, which reads 0 for @Nested classes."""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

DEFAULT_REPORTS = "aether/forge/forge-tests/target/failsafe-reports"


def main(reports):
    files = sorted(glob.glob(os.path.join(reports, "TEST-*.xml")))
    print("### Heavy forge tests: failsafe reports\n")
    state, reason = "ok", ""
    rows, new, known = [], [], []
    total = {"cases": 0, "failures": 0, "errors": 0, "skipped": 0}

    if not files:
        state, reason = "unknown", f"no failsafe report files under {reports}"

    for path in files:
        try:
            suite = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as error:
            state, reason = "unknown", f"unparseable report {os.path.basename(path)}: {error}"
            break

        name = suite.get("name", path).rsplit(".", 1)[-1]
        cases = suite.findall("testcase")
        failures = errors = skipped = 0

        for case in cases:
            if case.find("failure") is not None:
                failures += 1
                new.append(f"{name}.{case.get('name')}")
            elif case.find("error") is not None:
                errors += 1
                new.append(f"{name}.{case.get('name')}")
            skip = case.find("skipped")

            if skip is not None:
                skipped += 1
                # an aborted test carries its reason in the element TEXT ("org.opentest4j.TestAbortedException: known red #N: ..."), not in an attribute
                hit = re.search(r"known red #\d+[^\n]*", (skip.get("message") or "") + "\n" + (skip.text or ""))

                if hit:
                    known.append((name, case.get("name"), hit.group(0)[:200]))

        rows.append((name, len(cases), failures, errors, skipped, suite.get("time", "?")))
        total["cases"] += len(cases)
        total["failures"] += failures
        total["errors"] += errors
        total["skipped"] += skipped

    if state == "ok" and total["cases"] == 0:
        state, reason = "unknown", f"{len(files)} report file(s) but zero testcases"

    if state == "ok" and new:
        state = "red"

    print("| class | testcases | failures | errors | skipped | seconds |")
    print("|---|---|---|---|---|---|")

    for row in rows:
        print("| " + " | ".join(str(c) for c in row) + " |")

    print(f"\n**{len(files)} report files, {total['cases']} testcases, {total['failures']} failures, {total['errors']} errors, {total['skipped']} skipped**")
    print(f"\n**State: {state.upper()}**" + (f" ({reason})" if reason else ""))
    print(f"\n### Known reds ({len(known)}): ticketed failures that did not fail this run; a NEW red is a row of the table above\n")
    print("| class | test | known red |")
    print("|---|---|---|")

    for cls, test, message in known:
        print(f"| {cls} | {test} | {message} |")

    output = os.environ.get("GITHUB_OUTPUT")

    if output:
        with open(output, "a") as out:
            out.write(f"state={state}\n")
            out.write(f"reason={reason}\n")
            out.write("new_reds<<__END__\n" + "\n".join(new) + "\n__END__\n")
            out.write("known_reds<<__END__\n" + "\n".join(f"{c}.{n} ({m})" for c, n, m in known) + "\n__END__\n")

    return 1 if state == "unknown" else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_REPORTS))
