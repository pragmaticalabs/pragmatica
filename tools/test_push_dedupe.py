"""The decision of ci.yml's push-dedupe job (#3715), run through the real .github/scripts/push-dedupe.sh with a stub `gh` first on
PATH. The stub answers the two API calls the script makes from fixtures and records its arguments; `jq` is the real one.

Rules under test: a push to main or release-* skips only when a merge_group run of ci.yml on the SAME head sha concluded
success; every other state (no run, any other conclusion, lookup error, empty or malformed reply) runs everything; a push to another branch keeps the
open-PR rule; every other event never skips. forge-tests (the Maven cache save) must keep running on a queue-tested push.
"""
import json
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = Path(os.environ.get("PUSH_DEDUPE_SCRIPT", ROOT / ".github" / "scripts" / "push-dedupe.sh"))
CI = ROOT / ".github" / "workflows" / "ci.yml"
SHA = "d40018613ccb8f85b14194298ce8d88745d55113"
OTHER = "7b8a2b06cc86585e88ab37d8e82ff6f9e112c253"

STUB = """#!/usr/bin/env bash
echo "$*" >> "$STUB_LOG"
case "$*" in
  *actions/workflows/ci.yml/runs*)
    [ "${STUB_RUNS_RC:-0}" = 0 ] || { echo "HTTP 500" >&2; exit "$STUB_RUNS_RC"; }
    cat "$STUB_RUNS_FILE" ;;
  *"/pulls"*)
    [ "${STUB_PULLS_RC:-0}" = 0 ] || { echo "HTTP 500" >&2; exit "$STUB_PULLS_RC"; }
    printf '%s' "${STUB_BASES:-}" ;;
  *) echo "unexpected gh call: $*" >&2; exit 64 ;;
esac
"""


def run_row(run_id, conclusion="success", event="merge_group", sha=SHA):
    return {"id": run_id, "event": event, "head_sha": sha, "status": "completed" if conclusion else "in_progress",
            "conclusion": conclusion, "path": ".github/workflows/ci.yml"}


def runs(*rows):
    return json.dumps({"total_count": len(rows), "workflow_runs": list(rows)})


class Decision(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        (self.dir / "gh").write_text(STUB)
        (self.dir / "gh").chmod(0o755)
        self.log = self.dir / "gh.log"
        self.log.write_text("")

    def tearDown(self):
        self.tmp.cleanup()

    def decide(self, event="push", branch="release-1.0.0-rc6", sha=SHA, reply=None, runs_rc=0, bases="", pulls_rc=0):
        """Returns (skip, queue_tested, stdout). reply: the stubbed body of the runs lookup."""
        runs_file = self.dir / "runs.json"
        runs_file.write_text(reply if reply is not None else runs())
        out = self.dir / "github_output"
        out.write_text("")
        env = {"PATH": f"{self.dir}:{os.environ['PATH']}", "STUB_LOG": str(self.log), "STUB_RUNS_FILE": str(runs_file),
               "STUB_RUNS_RC": str(runs_rc), "STUB_BASES": bases, "STUB_PULLS_RC": str(pulls_rc), "GITHUB_OUTPUT": str(out),
               "EVENT": event, "BRANCH": branch, "REPO": "o/r", "OWNER": "o", "SHA": sha, "GH_TOKEN": "unused"}
        done = subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(done.returncode, 0, done.stderr)
        outputs = dict(line.split("=", 1) for line in out.read_text().splitlines())
        self.assertEqual(set(outputs), {"skip", "queue_tested"}, outputs)
        return outputs["skip"], outputs["queue_tested"], done.stdout

    def assert_runs_ci(self, **kw):
        skip, queue, stdout = self.decide(**kw)
        self.assertEqual((skip, queue), ("false", "false"), stdout)
        return stdout

    # --- push to a protected branch ---

    def test_a_successful_merge_group_run_on_the_same_sha_skips_and_names_the_run(self):
        skip, queue, stdout = self.decide(reply=runs(run_row(45514043526)))
        self.assertEqual((skip, queue), ("true", "true"))
        self.assertIn("45514043526", stdout)

    def test_the_lookup_asks_for_merge_group_runs_of_ci_yml_on_this_sha(self):
        self.decide(reply=runs(run_row(1)))
        call = self.log.read_text()
        self.assertIn("actions/workflows/ci.yml/runs", call)
        self.assertIn("event=merge_group", call)
        self.assertIn(f"head_sha={SHA}", call)

    def test_main_is_a_protected_branch_too(self):
        self.assertEqual(self.decide(branch="main", reply=runs(run_row(7)))[:2], ("true", "true"))

    def test_a_failed_merge_group_run_runs_ci(self):
        self.assert_runs_ci(reply=runs(run_row(1, conclusion="failure")))

    def test_every_non_success_conclusion_runs_ci(self):
        for conclusion in ("cancelled", "timed_out", "skipped", "neutral", "action_required", "startup_failure"):
            with self.subTest(conclusion=conclusion):
                self.assert_runs_ci(reply=runs(run_row(1, conclusion=conclusion)))

    def test_an_unfinished_merge_group_run_runs_ci(self):
        self.assert_runs_ci(reply=runs(run_row(1, conclusion=None)))

    def test_no_merge_group_run_is_a_direct_push_and_runs_ci(self):
        self.assert_runs_ci(reply=runs())

    def test_one_failed_and_one_successful_run_of_the_sha_skips_on_the_successful_one(self):
        skip, queue, stdout = self.decide(reply=runs(run_row(1, conclusion="failure"), run_row(2)))
        self.assertEqual((skip, queue), ("true", "true"))
        self.assertIn("run 2 ", stdout)

    def test_a_successful_run_of_another_sha_runs_ci(self):
        self.assert_runs_ci(reply=runs(run_row(1, sha=OTHER)))

    def test_a_successful_run_of_another_event_runs_ci(self):
        for event in ("push", "pull_request", "workflow_dispatch"):
            with self.subTest(event=event):
                self.assert_runs_ci(reply=runs(run_row(1, event=event)))

    def test_a_lookup_error_runs_ci_even_when_a_body_was_printed(self):
        self.assert_runs_ci(reply=runs(run_row(1)), runs_rc=1)

    def test_an_empty_reply_runs_ci(self):
        self.assert_runs_ci(reply="")

    def test_malformed_replies_run_ci(self):
        for reply in ("not json", "{", "[]", "null", '{"workflow_runs": null}', '{"workflow_runs": [{"id": "x"}]}',
                      json.dumps({"workflow_runs": [run_row("abc")]}), json.dumps({"workflow_runs": [run_row(None)]})):
            with self.subTest(reply=reply):
                self.assert_runs_ci(reply=reply)

    def test_a_head_sha_that_is_not_a_full_sha_runs_ci_without_asking(self):
        for sha in ("", "d40018613c", SHA.upper(), SHA + "0", "x" * 40):
            with self.subTest(sha=sha):
                self.assert_runs_ci(sha=sha, reply=runs(run_row(1, sha=sha)))
                self.assertEqual(self.log.read_text(), "")

    def test_the_decision_is_logged_with_its_reason_when_ci_runs(self):
        self.assertIn("running CI", self.assert_runs_ci(reply=runs()))
        self.assertIn("lookup failed", self.assert_runs_ci(reply=runs(run_row(1)), runs_rc=1))

    # --- the other triggers ---

    def test_a_feature_branch_push_keeps_the_open_pr_rule(self):
        self.assertEqual(self.decide(branch="feature/x", bases="release-1.0.0-rc6\n")[:2], ("true", "false"))
        self.assertEqual(self.decide(branch="feature/x", bases="")[:2], ("false", "false"))
        self.assertEqual(self.decide(branch="feature/x", bases="some-topic\n")[:2], ("false", "false"))
        self.assertEqual(self.decide(branch="feature/x", bases="some-topic\nfeature/y\n")[:2], ("true", "false"))
        self.assertEqual(self.decide(branch="feature/x", pulls_rc=1)[:2], ("false", "false"))

    def test_a_feature_branch_push_never_asks_for_merge_group_runs_even_when_one_exists(self):
        self.assertEqual(self.decide(branch="feature/x", reply=runs(run_row(1)))[:2], ("false", "false"))
        self.assertNotIn("actions/workflows", self.log.read_text())

    def test_pull_request_and_merge_group_events_never_skip(self):
        for event, branch in (("pull_request", "fix/3715"), ("merge_group", "gh-readonly-queue/release-1.0.0-rc6/pr-1-abc"),
                              ("pull_request", "release-1.0.0-rc6"), ("workflow_dispatch", "release-1.0.0-rc6")):
            with self.subTest(event=event, branch=branch):
                self.assert_runs_ci(event=event, branch=branch, reply=runs(run_row(1)), bases="release-1.0.0-rc6\n")
                self.assertEqual(self.log.read_text(), "")


class Wiring(unittest.TestCase):
    """What ci.yml must keep so the script's two outputs mean what the jobs assume."""

    @classmethod
    def setUpClass(cls):
        cls.ci = CI.read_text().split("\njobs:\n", 1)[1]

    def job(self, name):
        match = re.search(rf"(?ms)^  {re.escape(name)}:\n(.*?)(?=^  [a-z][a-z0-9-]*:\n|\Z)", self.ci)
        self.assertIsNotNone(match, name)
        return match.group(1)

    def test_push_dedupe_may_read_actions_and_runs_the_script(self):
        job = self.job("push-dedupe")
        self.assertIn("actions: read", job)
        self.assertIn("run: .github/scripts/push-dedupe.sh", job)
        self.assertIn("SHA: ${{ github.sha }}", job)
        self.assertIn("queue_tested: ${{ steps.lookup.outputs.queue_tested }}", job)

    def test_forge_tests_keeps_running_on_a_queue_tested_push_under_its_own_name(self):
        job = self.job("forge-tests")
        self.assertIn("if: needs.push-dedupe.outputs.skip != 'true' || needs.push-dedupe.outputs.queue_tested == 'true'", job)
        self.assertIn("skip == 'true' && needs.push-dedupe.outputs.queue_tested != 'true' && 'forge-tests (duplicate push run, skipped)'", job)
        self.assertIn("github.event_name == 'push' && steps.prime.outcome == 'success'", job)

    def test_no_other_job_runs_on_a_queue_tested_push(self):
        for name in re.findall(r"(?m)^  ([a-z][a-z0-9-]*):\n", self.ci):
            if name in ("push-dedupe", "forge-tests"):
                continue
            with self.subTest(job=name):
                self.assertNotIn("queue_tested", self.job(name))
                self.assertIn("needs.push-dedupe.outputs.skip != 'true'", self.job(name))


if __name__ == "__main__":
    unittest.main()
