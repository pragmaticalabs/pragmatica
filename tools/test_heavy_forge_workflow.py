"""Pins for the heavy-forge nightly's reporting (#2017): what the summary says about a night, and when the notify job may close the issue.

A run that examined nothing must never read as green: no report files, an unparseable report and zero testcases are UNKNOWN, the summary
step fails on them (pipefail keeps its exit code through the `tee`), and the notify job closes the issue ONLY on a green job whose state is
`ok`. The workflow's own shell blocks are extracted from heavy-forge.yml and executed, so the pins cannot drift from what runs."""
from pathlib import Path
import os
import re
import shutil
import stat
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github" / "workflows" / "heavy-forge.yml"
SUMMARY = ROOT / "tools" / "heavy-forge-summary.py"

GOOD = '<testsuite name="org.x.AlphaTest" tests="2" time="1.5"><testcase name="a"/><testcase name="b"/></testsuite>'
KNOWN = ('<testsuite name="org.x.BetaTest" tests="1" time="2"><testcase name="t"><skipped type="org.opentest4j.TestAbortedException">'
         '<![CDATA[org.opentest4j.TestAbortedException: known red #1717: ConditionTimeoutException: x\n\tat Y]]></skipped></testcase></testsuite>')
FAILED = '<testsuite name="org.x.GammaTest" tests="1" time="3"><testcase name="g"><failure message="boom">trace</failure></testcase></testsuite>'
EMPTY = '<testsuite name="org.x.EmptyTest" tests="0" time="0"></testsuite>'


def run_block(step_name):
    """The `run: |` body of the step (or notify-job step) called `step_name`, de-indented."""
    lines = WORKFLOW.read_text().splitlines()
    start = next(i for i, line in enumerate(lines) if line.strip() == f"- name: {step_name}")
    run = next(i for i in range(start, len(lines)) if lines[i].strip() == "run: |")
    indent = len(lines[run + 1]) - len(lines[run + 1].lstrip())
    body = []

    for line in lines[run + 1:]:
        if line.strip() and len(line) - len(line.lstrip()) < indent:
            break
        body.append(line[indent:] if line.strip() else "")

    return "\n".join(body) + "\n"


def parse_outputs(text):
    outputs, key, buffer = {}, None, []

    for line in text.splitlines():
        if key is not None:
            if line == "__END__":
                outputs[key], key, buffer = "\n".join(buffer), None, []
            else:
                buffer.append(line)
        elif "<<__END__" in line:
            key = line.split("<<")[0]
        elif "=" in line:
            name, _, value = line.partition("=")
            outputs[name] = value

    return outputs


class SummaryScript(unittest.TestCase):
    def summarise(self, files):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory) / "reports"
            output = Path(directory) / "out"

            if files is not None:
                reports.mkdir()
                for name, content in files.items():
                    (reports / name).write_text(content)

            result = subprocess.run(["python3", "-B", str(SUMMARY), str(reports)], capture_output=True, text=True,
                                    env={**os.environ, "GITHUB_OUTPUT": str(output)})

            return result, parse_outputs(output.read_text() if output.exists() else "")

    def test_real_reports_are_ok(self):
        result, outputs = self.summarise({"TEST-a.xml": GOOD})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(outputs["state"], "ok")

    def test_a_known_red_is_listed_apart_and_does_not_make_the_night_red(self):
        result, outputs = self.summarise({"TEST-a.xml": GOOD, "TEST-b.xml": KNOWN})
        self.assertEqual(outputs["state"], "ok")
        self.assertIn("BetaTest.t (known red #1717", outputs["known_reds"])
        self.assertEqual(outputs["new_reds"], "")

    def test_a_failure_is_a_new_red(self):
        result, outputs = self.summarise({"TEST-a.xml": GOOD, "TEST-g.xml": FAILED})
        self.assertEqual(result.returncode, 0)
        self.assertEqual(outputs["state"], "red")
        self.assertIn("GammaTest.g", outputs["new_reds"])

    def test_zero_testcases_is_unknown_and_fails_the_step(self):
        result, outputs = self.summarise({"TEST-e.xml": EMPTY})
        self.assertEqual(result.returncode, 1)
        self.assertEqual(outputs["state"], "unknown")
        self.assertIn("zero testcases", outputs["reason"])

    def test_a_corrupt_report_is_unknown_and_fails_the_step(self):
        result, outputs = self.summarise({"TEST-a.xml": GOOD, "TEST-bad.xml": "<testsuite><testcase"})
        self.assertEqual(result.returncode, 1)
        self.assertEqual(outputs["state"], "unknown")
        self.assertIn("unparseable", outputs["reason"])

    def test_a_missing_or_empty_report_directory_is_unknown(self):
        for files in (None, {}):
            result, outputs = self.summarise(files)
            self.assertEqual(result.returncode, 1)
            self.assertEqual(outputs["state"], "unknown")
            self.assertIn("no failsafe report files", outputs["reason"])


class SummaryStep(unittest.TestCase):
    def execute(self, report):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            (work / "tools").mkdir()
            shutil.copy(SUMMARY, work / "tools" / "heavy-forge-summary.py")
            reports = work / "aether/forge/forge-tests/target/failsafe-reports"
            reports.mkdir(parents=True)
            (reports / "TEST-a.xml").write_text(report)

            return subprocess.run(["bash", "-e", "-c", run_block("Summarise failsafe reports")], cwd=work, capture_output=True, text=True,
                                  env={**os.environ, "GITHUB_STEP_SUMMARY": str(work / "summary"), "GITHUB_OUTPUT": str(work / "out")})

    def test_a_corrupt_report_fails_the_step_through_the_tee(self):
        # GitHub's default shell is `bash -e {0}` WITHOUT pipefail: the step block must set it itself, or this exits 0
        self.assertNotEqual(self.execute("<testsuite").returncode, 0)

    def test_a_real_report_passes_the_step(self):
        result = self.execute(GOOD)
        self.assertEqual(result.returncode, 0, result.stderr)


class NotifyJob(unittest.TestCase):
    def notify(self, result, state, open_issue=None):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            (work / "bin").mkdir()
            gh = work / "bin" / "gh"
            gh.write_text('#!/usr/bin/env bash\necho "gh $*" >> "$STUB_LOG"\n'
                          'if [ "$1 $2" = "issue list" ] && [ -n "${STUB_OPEN:-}" ]; then echo "$STUB_OPEN"; fi\nexit 0\n')
            gh.chmod(gh.stat().st_mode | stat.S_IXUSR)
            env = {**os.environ, "PATH": f"{work / 'bin'}:{os.environ['PATH']}", "STUB_LOG": str(work / "log"), "STUB_OPEN": open_issue or "",
                   "GH_REPO": "r", "RUN_URL": "https://x/run/1", "SHA": "abc", "RESULT": result, "NEW_REDS": "A.x", "KNOWN_REDS": "", "REASON": "r"}

            if state is not None:
                env["STATE"] = state
            else:
                env.pop("STATE", None)

            subprocess.run(["bash", "-e", "-c", run_block("Open, update or close the heavy-nightly issue")], cwd=work, env=env, check=True, capture_output=True)

            return (work / "log").read_text() if (work / "log").exists() else ""

    def test_red_night_without_an_issue_creates_one_titled_new_reds(self):
        log = self.notify("failure", "red")
        self.assertIn("gh issue create --title Heavy nightly: new reds", log)

    def test_an_unknown_night_creates_an_issue_titled_unknown_result(self):
        for state in ("unknown", "", None):
            log = self.notify("failure", state)
            self.assertIn("gh issue create --title Heavy nightly: unknown result", log, f"state={state!r}")
            self.assertNotIn("Heavy nightly: new reds", log, f"state={state!r}")

    def test_the_title_follows_the_latest_night_on_an_open_issue(self):
        self.assertIn("gh issue edit 42 --title Heavy nightly: new reds", self.notify("failure", "red", "42"))
        self.assertIn("gh issue edit 42 --title Heavy nightly: unknown result", self.notify("failure", "unknown", "42"))

    def test_red_night_with_an_open_issue_comments(self):
        log = self.notify("failure", "red", "42")
        self.assertIn("gh issue comment 42", log)
        self.assertNotIn("issue close", log)

    def test_green_night_closes_the_open_issue(self):
        self.assertIn("gh issue close 42", self.notify("success", "ok", "42"))

    def test_green_night_without_an_issue_does_nothing(self):
        log = self.notify("success", "ok")
        self.assertNotIn("issue create", log)
        self.assertNotIn("issue close", log)

    def test_an_unknown_night_never_closes_the_issue(self):
        for result in ("success", "failure"):
            for state in ("unknown", "", None, "garbage"):
                log = self.notify(result, state, "42")
                self.assertNotIn("issue close", log, f"result={result} state={state!r}")
                self.assertIn("gh issue comment 42", log, f"result={result} state={state!r}")

    def test_a_failed_job_with_readable_reports_never_closes(self):
        self.assertNotIn("issue close", self.notify("failure", "ok", "42"))


if __name__ == "__main__":
    unittest.main()
