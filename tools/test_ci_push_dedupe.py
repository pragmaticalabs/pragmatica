"""Pins for ci.yml's merge-queue dedupe of release-* pushes: when may a push skip the test jobs, and what the skipped jobs post.

A push to release-* may skip the test jobs only when exactly one merge_group run of ci.yml for the SAME head sha exists and it
completed with conclusion success; every other answer (wrong sha, wrong event, wrong workflow, failed, running, two runs, an API
error, garbage, empty) must run the tests. The step's own shell block is extracted from ci.yml and executed against a fake `gh`
that replays recorded JSON, so these pins cannot drift from what the workflow runs. The structure pins read the job blocks: every
test job must honour skip_tests, every required single-instance job must rename itself when skipped (a skipped check named like a
required context SATISFIES it), and static-gates and forge-tests must keep running."""
from pathlib import Path
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"

SHA = "de5b248f13aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
OTHER = "0123456789abcdef0123456789abcdef01234567"
CI = ".github/workflows/ci.yml"


def run(sha=SHA, event="merge_group", path=CI, status="completed", conclusion="success", name="CI"):
    return {"id": 45474357274, "name": name, "event": event, "path": path, "status": status, "conclusion": conclusion,
            "head_sha": sha, "run_attempt": 1}


def answer(*runs):
    return json.dumps({"total_count": len(runs), "workflow_runs": list(runs)})


def step_block(workflow_text, step_id):
    """The `run: |` body of the step with `id: <step_id>`, de-indented."""
    lines = workflow_text.splitlines()
    ident = next(i for i, line in enumerate(lines) if line.strip() == f"id: {step_id}")
    start = next(i for i in range(ident, len(lines)) if lines[i].strip() == "run: |")
    indent = len(lines[start + 1]) - len(lines[start + 1].lstrip())
    body = []

    for line in lines[start + 1:]:
        if line.strip() and len(line) - len(line.lstrip()) < indent:
            break
        body.append(line[indent:] if line.strip() else "")

    return "\n".join(body) + "\n"


def jobs(workflow_text):
    """job id -> its text block, split on the two-space job keys under `jobs:`."""
    text = workflow_text[workflow_text.index("\njobs:\n"):]
    keys = list(re.finditer(r"^  ([a-z0-9-]+):\s*$", text, re.M))
    return {m.group(1): text[m.start():(keys[i + 1].start() if i + 1 < len(keys) else len(text))] for i, m in enumerate(keys)}


class LookupTest(unittest.TestCase):
    """The push-dedupe step, executed. `lookup(...)` returns (skip, skip_tests, gh calls)."""
    workflow_text = None

    @classmethod
    def setUpClass(cls):
        cls.workflow_text = WORKFLOW.read_text()

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp(prefix="ci-push-dedupe-"))
        gh = self.dir / "gh"
        gh.write_text('#!/bin/bash\n'
                      'echo "$*" >> "$FAKE_GH_LOG"\n'
                      'if [ "$FAKE_GH_MODE" = error ]; then echo "HTTP 502" >&2; exit 1; fi\n'
                      'case "$*" in *actions/runs*) cat "$FAKE_GH_JSON" ;; esac\n')
        gh.chmod(gh.stat().st_mode | stat.S_IEXEC)

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def lookup(self, body, event="push", branch="release-1.0.0-rc6", sha=SHA, mode="ok", text=None):
        script = self.dir / "step.sh"
        script.write_text(step_block(text or self.workflow_text, "lookup"))
        (self.dir / "answer.json").write_text(body)
        out = self.dir / "github-output"
        out.write_text("")
        log = self.dir / "gh-calls"
        log.write_text("")
        env = dict(os.environ, PATH=f"{self.dir}:{os.environ['PATH']}", GH_TOKEN="t", EVENT=event, BRANCH=branch, SHA=sha,
                   REPO="o/r", OWNER="o", GITHUB_OUTPUT=str(out), FAKE_GH_JSON=str(self.dir / "answer.json"),
                   FAKE_GH_LOG=str(log), FAKE_GH_MODE=mode)
        done = subprocess.run(["bash", "-e", str(script)], env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(done.returncode, 0, done.stderr + done.stdout)
        values = dict(line.split("=", 1) for line in out.read_text().splitlines())
        return values["skip"], values["skip_tests"], log.read_text()

    def assertRuns(self, body, **kw):
        skip, skip_tests, _ = self.lookup(body, **kw)
        self.assertEqual((skip, skip_tests), ("false", "false"), f"{kw}: {body}")

    def test_match_skips_the_tests_and_nothing_else(self):
        skip, skip_tests, calls = self.lookup(answer(run()))
        self.assertEqual((skip, skip_tests), ("false", "true"))
        self.assertIn(f"head_sha={SHA}", calls)
        self.assertIn("event=merge_group", calls)

    def test_match_among_runs_of_other_workflows_of_the_same_sha(self):
        other = run(path=".github/workflows/changelog.yml", name="Changelog fragment")
        self.assertEqual(self.lookup(answer(other, run(), run(event="push")))[:2], ("false", "true"))

    def test_wrong_sha(self):
        self.assertRuns(answer(run(sha=OTHER)))

    def test_event_push(self):
        self.assertRuns(answer(run(event="push")))

    def test_event_pull_request(self):
        self.assertRuns(answer(run(event="pull_request")))

    def test_other_workflow(self):
        self.assertRuns(answer(run(path=".github/workflows/changelog.yml", name="Changelog fragment")))

    def test_failed(self):
        self.assertRuns(answer(run(conclusion="failure")))

    def test_cancelled(self):
        self.assertRuns(answer(run(conclusion="cancelled")))

    def test_in_progress(self):
        self.assertRuns(answer(run(status="in_progress", conclusion=None)))

    def test_success_conclusion_on_a_run_not_completed_is_not_trusted(self):
        # GitHub never reports this pair; the check is the stated rule (completed AND success), so it is pinned on its own
        self.assertRuns(answer(run(status="in_progress", conclusion="success")))

    def test_queued(self):
        self.assertRuns(answer(run(status="queued", conclusion=None)))

    def test_two_runs_one_failed_one_success_is_ambiguous(self):
        self.assertRuns(answer(run(conclusion="failure"), run()))

    def test_two_successful_runs_are_ambiguous(self):
        self.assertRuns(answer(run(), run()))

    def test_api_error(self):
        self.assertRuns(answer(run()), mode="error")

    def test_empty(self):
        self.assertRuns(answer())

    def test_garbage_answer(self):
        self.assertRuns("<html>502</html>")

    def test_answer_without_runs_key(self):
        self.assertRuns('{"message":"Not Found"}')

    def test_answer_is_not_an_object(self):
        self.assertRuns('"x"')

    def test_main_branch_never_looks_up_the_queue(self):
        skip, skip_tests, calls = self.lookup(answer(run()), branch="main")
        self.assertEqual((skip, skip_tests), ("false", "false"))
        self.assertNotIn("actions/runs", calls)

    def test_feature_branch_never_looks_up_the_queue(self):
        skip, skip_tests, calls = self.lookup(answer(run()), branch="feature/x")
        self.assertEqual(skip_tests, "false")
        self.assertNotIn("actions/runs", calls)
        self.assertIn("pulls", calls)

    def test_overhaul_branch_never_looks_up_the_queue(self):
        self.assertRuns(answer(run()), branch="overhaul/x")

    def test_non_push_events_never_skip_tests(self):
        for event in ("pull_request", "merge_group", "workflow_dispatch"):
            skip, skip_tests, calls = self.lookup(answer(run()), event=event)
            self.assertEqual((skip, skip_tests), ("false", "false"), event)
            self.assertNotIn("actions/runs", calls)

    def test_a_release_push_is_never_skipped_whole(self):
        self.assertEqual(self.lookup(answer(run()))[0], "false")

    def test_the_other_release_branches_use_the_lookup(self):
        self.assertEqual(self.lookup(answer(run()), branch="release-1.0.0-rc7")[:2], ("false", "true"))


class StructureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = WORKFLOW.read_text()
        cls.jobs = jobs(cls.text)

    TEST_JOBS = ["test-group", "build-and-test", "ember-shard", "consensus-shard", "quarantine", "stub-suites"]
    KEEPS_RUNNING = ["static-gates", "forge-tests"]
    RENAMES = {"build-and-test": "merge queue tested this commit", "stub-suites": "merge queue tested this commit"}
    # Required contexts of ruleset 26557358 that a skipped job must never post under (ember/consensus matrix jobs post their template)
    REQUIRED = ["build-and-test", "forge-tests", "stub-suites"]

    def header(self, job):
        return re.split(r"^    steps:", self.jobs[job], flags=re.M)[0]

    def test_every_test_job_honours_skip_tests(self):
        for job in self.TEST_JOBS:
            with self.subTest(job=job):
                self.assertRegex(self.header(job), r"if:.*needs\.push-dedupe\.outputs\.skip_tests != 'true'")
                self.assertRegex(self.header(job), r"if:.*needs\.push-dedupe\.outputs\.skip != 'true'")

    def test_jobs_that_keep_running_do_not_read_skip_tests(self):
        for job in self.KEEPS_RUNNING:
            with self.subTest(job=job):
                self.assertNotIn("push-dedupe.outputs.skip_tests", self.jobs[job])
                self.assertIn("needs.push-dedupe.outputs.skip != 'true'", self.header(job))

    def test_forge_tests_keeps_its_push_only_cache_steps(self):
        block = self.jobs["forge-tests"]
        self.assertEqual(block.count("github.event_name == 'push'"), 3)
        self.assertIn("actions/cache/save@v4", block)

    def test_required_single_instance_jobs_rename_when_skipped(self):
        for job in self.REQUIRED:
            with self.subTest(job=job):
                name = re.search(r"^    name: (.*)$", self.header(job), re.M).group(1)
                self.assertRegex(name, rf"skip == 'true' && '{job} \(duplicate push run, skipped\)'")
                if job in self.RENAMES:
                    self.assertRegex(name, rf"skip_tests == 'true' && '{job} \({self.RENAMES[job]}, skipped\)'")
                self.assertTrue(name.rstrip(" }").endswith(f"|| '{job}'"), name)

    def test_matrix_jobs_post_templates_no_required_context_uses(self):
        for job, template in (("test-group", "test-group-${{ matrix.group }}"), ("ember-shard", "ember-shard-${{ matrix.shard }}"),
                              ("consensus-shard", "consensus-shard-${{ matrix.shard }}")):
            with self.subTest(job=job):
                self.assertIn(f"    name: {template}\n", self.header(job))

    def test_build_and_test_still_runs_always_and_needs_its_gates(self):
        header = self.header("build-and-test")
        self.assertIn("if: always() &&", header)
        self.assertIn("needs: [push-dedupe, static-gates, test-group]", header)

    def test_only_push_dedupe_holds_actions_read(self):
        grants = re.findall(r"^\s+actions: read\s*$", self.text, re.M)
        self.assertEqual(len(grants), 1)
        self.assertRegex(self.jobs["push-dedupe"], r"(?m)^      actions: read\s*$")

    def test_push_dedupe_publishes_both_outputs(self):
        block = self.jobs["push-dedupe"]
        self.assertIn("skip: ${{ steps.lookup.outputs.skip }}", block)
        self.assertIn("skip_tests: ${{ steps.lookup.outputs.skip_tests }}", block)


if __name__ == "__main__":
    unittest.main()
