"""Tests of the TLA+ workflows' wiring, read as text (no YAML library: the runners and a laptop both have only python3).

  - the jar: every job that uses tla2tools.jar downloads it from the owner-approved asset of this repository, verifies the
    sha256 pin on a cache hit too, and keys its cache on the sha; the workflow, the README and the tests agree on the pin (F1);
  - the pull-request job of the paths-list check exists and runs the check (F7);
  - the PR checks are fit to be REQUIRED checks (QueueAndInstrument, MergeGroupDryRun, InstrumentFromBase): they report on the merge
    queue, the merge group is judged per queued squash commit with the excuses of the PR each names, and the scripts and their
    self-tests come from the base branch, so a branch older than the instrument is judged instead of reporting "NO TESTS RAN";
  - the list of profiles is one list: run.sh's validation line is the source, and results-check.py, plan-matrix.py, the
    workflow's dispatch input and the README's usage line must name exactly those profiles (the heavy profile once wrote
    rows that results-check.py called malformed).
"""
import importlib.util
import os
import re
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
MODELS_YML = os.path.join(REPO, '.github', 'workflows', 'tla-models.yml')
PR_YML = os.path.join(REPO, '.github', 'workflows', 'tla-pr-checks.yml')
README = os.path.join(HERE, 'README.md')
ASSET = re.compile(r'^https://github\.com/pragmaticalabs/pragmatica/releases/download/(tla2tools-[0-9.]+)/tla2tools\.jar$')


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def jobs(text):
    """{job name: text of its block} for the top-level `jobs:` of a workflow."""
    body = text.split('\njobs:\n', 1)[1]
    parts = re.split(r'^  ([A-Za-z][A-Za-z0-9_-]*):[ \t]*$', body, flags=re.M)
    return {parts[i]: parts[i + 1] for i in range(1, len(parts), 2)}


def env_value(text, name):
    m = re.search(rf'^  {name}: (\S+)[ \t]*$', text, re.M)
    assert m, f'env {name} not found'
    return m.group(1)


def load(name, fname):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, fname))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class JarPin(unittest.TestCase):
    def setUp(self):
        self.wf = read(MODELS_YML)
        self.jobs = jobs(self.wf)
        self.users = {n: b for n, b in self.jobs.items() if 'tla2tools.jar' in b}

    def test_the_download_url_is_the_approved_asset_of_this_repository(self):
        self.assertRegex(env_value(self.wf, 'TLA_JAR_URL'), ASSET)

    def test_no_job_downloads_from_anywhere_else(self):
        self.assertNotIn('tlaplus', self.wf, 'the TLA+ project release page is not a source of the jar')
        curls = re.findall(r'^\s*curl .*$', self.wf, re.M)
        self.assertGreaterEqual(len(curls), 2)
        for c in curls:
            self.assertIn('"${TLA_JAR_URL}"', c)
            self.assertNotIn('http', c)

    def test_every_job_that_uses_the_jar_downloads_caches_and_verifies_it(self):
        self.assertEqual(sorted(self.users), ['model-check', 'selftests'])
        for name, b in self.users.items():
            with self.subTest(job=name):
                self.assertEqual(b.count('curl -fsSL -o .tla/tla2tools.jar "${TLA_JAR_URL}"'), 1)
                self.assertEqual(b.count('key: tla2tools-${{ env.TLA_SHA256 }}'), 1)
                self.assertEqual(b.count('sha256sum -c -'), 1)
                m = re.search(r'- name: Verify tla2tools.jar checksum\n(\s+)run: echo "\$\{TLA_SHA256\}  \.tla/tla2tools\.jar" \| sha256sum -c -', b)
                self.assertIsNotNone(m, 'the verify step reads TLA_SHA256 and carries no `if:` (it runs on a cache hit too)')
                self.assertLess(b.index('Download tla2tools.jar'), b.index('Verify tla2tools.jar checksum'))
                self.assertLess(b.index('Verify tla2tools.jar checksum'), b.index('tools/tla/'), 'the jar is verified before anything uses it')

    def test_the_cache_key_carries_the_sha_and_no_version(self):
        self.assertNotIn('TLA_VERSION', self.wf)

    def test_the_pin_is_a_sha256_the_readme_states_with_the_same_asset(self):
        sha = env_value(self.wf, 'TLA_SHA256')
        self.assertRegex(sha, r'^[0-9a-f]{64}$')
        readme = read(README)
        self.assertIn(sha, readme)
        self.assertIn(ASSET.match(env_value(self.wf, 'TLA_JAR_URL')).group(1), readme)
        self.assertIn('To move the pin', readme)


class PullRequestJob(unittest.TestCase):
    def setUp(self):
        self.pr = read(PR_YML)

    def test_the_paths_list_check_runs_on_a_pull_request(self):
        self.assertRegex(self.pr, r'(?m)^on:\n  pull_request:')
        job = jobs(self.pr).get('fragment-paths')
        self.assertIsNotNone(job, 'a PR job that runs fragment-paths.py --check')
        self.assertIn('python3 tools/tla/fragment-paths.py --check', job)
        self.assertNotIn('run.sh', job, 'no TLC on a pull request')

    def test_the_job_runs_the_self_tests_of_what_it_runs(self):
        job = jobs(self.pr)['fragment-paths']
        self.assertIn("-p 'test_fragment_paths.py'", job)
        self.assertIn("-p 'test_workflow.py'", job)

    def test_the_models_workflow_still_has_no_pull_request_trigger(self):
        self.assertNotRegex(read(MODELS_YML), r'(?m)^  pull_request')

    def test_the_selftests_job_of_the_models_workflow_runs_every_python_test_file(self):
        job = jobs(read(MODELS_YML))['selftests']
        for t in sorted(f for f in os.listdir(HERE) if re.fullmatch(r'test_(fragment_paths|plan_matrix|results_check|workflow)\.py', f)):
            self.assertIn(f"-p '{t}'", job, f'{t} is not run by the selftests job')


def steps(job):
    """{step name: text of the step} of one job block (steps start at `      - name: `)."""
    parts = re.split(r'^      - name: (.*)$', job, flags=re.M)
    return {parts[i].strip(): parts[i + 1] for i in range(1, len(parts), 2)}


def step_run(job, name):
    """The shell text of the `run: |` block of the named step, dedented."""
    body = steps(job)[name]
    m = re.search(r'^        run: \|\n((?:          .*\n|\n)+)', body + '\n', re.M)
    assert m, f'step {name!r} has no run block'
    return '\n'.join(l[10:] for l in m.group(1).split('\n'))


CHECKS = {  # job -> (scripts it runs, the self-tests of those)
    'fragment-touched': ('touched.py', 'test_touched.py'),
    'evidence-rows': ('results-check.py', 'test_results_check.py'),
    'fragment-paths': ('fragment-paths.py', 'test_fragment_paths.py'),
}


class QueueAndInstrument(unittest.TestCase):
    def setUp(self):
        self.pr = read(PR_YML)
        self.jobs = jobs(self.pr)

    def test_the_workflow_reports_on_the_merge_queue(self):
        self.assertRegex(self.pr, r"(?m)^  merge_group:\n    branches:\n      - 'release-\*'")
        self.assertEqual(sorted(self.jobs), ['evidence-rows', 'fragment-paths', 'fragment-touched'])

    def test_a_merge_group_run_is_never_cancelled_by_another(self):
        self.assertIn('group: tla-pr-checks-${{ github.event.pull_request.number || github.sha }}', self.pr)
        self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", self.pr)

    def test_every_job_checks_out_what_the_event_is_about(self):
        for name, j in self.jobs.items():
            self.assertIn('ref: ${{ github.event.pull_request.head.sha || github.sha }}', j, name)
            self.assertIn('fetch-depth: 0', j, name)

    def test_the_instrument_step_is_one_text_in_every_job_apart_from_its_file_list(self):
        texts = {}
        for name, j in self.jobs.items():
            body = steps(j)['Instrument from the base']
            body = re.sub(r'^        if: .*\n', '', body, flags=re.M)
            body = re.split(r'\n\s*\n', body.rstrip())[0]      # the step ends at the first blank line (comments of the next step follow it)
            texts[name] = re.sub(r'NEED: .*', 'NEED: x', body)
        self.assertEqual(len(set(texts.values())), 1, 'the three instrument steps differ')

    def test_each_job_reads_the_scripts_it_runs_from_the_base_and_needs_them_there(self):
        for name, (script, test) in CHECKS.items():
            j = self.jobs[name]
            need = re.search(r'NEED: (.*)', steps(j)['Instrument from the base']).group(1).split()
            self.assertIn(script, need, name)
            self.assertIn(test, need, name)
            self.assertIn(f'"$TLA_INST/tools/tla/{script}"', j, name)
            self.assertIn(f"discover -s \"$TLA_INST/tools/tla\" -p '{test}'", j, name)
            self.assertIn('INSTRUMENT ABSENT:', j, name)

    def test_the_heads_own_copy_runs_only_when_the_change_edits_tools_tla(self):
        head_copy = re.compile(r"(?<!TLA_INST/)tools/tla/(touched|results-check|fragment-paths)\.py|discover -s tools/tla -p 'test_(touched|results_check|fragment_paths)\.py'")
        for name, j in self.jobs.items():
            for step, body in steps(j).items():
                if step == 'Instrument from the base':
                    continue
                code = '\n'.join(l for l in body.split('\n') if not l.lstrip().startswith('#'))
                if head_copy.search(code):
                    self.assertIn('TLA_HEAD_OWN', code, f'{name}: {step!r} runs the head copy of a judge unconditionally')

    def test_nothing_hands_the_judge_a_pr_editable_default(self):
        j = self.jobs['fragment-paths']
        self.assertIn('--models tools/tla/models.tsv', j, 'the base script defaults --models to its own directory: the data is the head\'s')

    def test_no_step_ends_in_a_pass_over_an_unexamined_merge_group(self):
        run = step_run(self.jobs['fragment-touched'], 'Code under a tla fragment changed without its fragment')
        self.assertIn('nothing was examined', run)

    def test_the_evidence_row_job_says_out_loud_what_a_merge_group_does_not_recheck(self):
        j = steps(self.jobs['evidence-rows'])
        self.assertIn("github.event_name == 'merge_group'", j['Merge queue - evidence rows verified on the pull request'])
        for n, b in j.items():
            if n != 'Merge queue - evidence rows verified on the pull request' and not n.startswith('Checkout'):
                self.assertIn("github.event_name == 'pull_request'", b, f'{n!r} would run on a merge group, where the squash dropped the ancestry it reads')


GIT_ENV = dict(os.environ, GIT_AUTHOR_NAME='t', GIT_AUTHOR_EMAIL='t@t', GIT_COMMITTER_NAME='t', GIT_COMMITTER_EMAIL='t@t',
               GIT_CONFIG_GLOBAL='/dev/null', GIT_CONFIG_SYSTEM='/dev/null')
FRAG = '    /// ```tla M:Step\n    /// Step == x\' = x + 1\n    /// ```\n'
JAVA = 'class A {\n' + FRAG + '    int step() {\n        int b = 2;\n        return b;\n    }\n\n    int other() {\n        return 9;\n    }\n}\n'
IN_REGION = JAVA.replace('int b = 2;', 'int b = 3;')
NEW_FRAG = IN_REGION.replace("x + 1", "x + 2")
OUTSIDE = JAVA.replace('return 9;', 'return 10;')


def read_env(scratch):
    with open(os.path.join(scratch.d, '.runner', 'env'), encoding='utf-8') as f:
        return dict(l.split('=', 1) for l in f.read().split())


class Scratch:
    def __init__(self):
        self.d = tempfile.mkdtemp()
        self.g('init', '-q', '-b', 'main')
        self.bin = os.path.join(self.d, '.bin')
        os.makedirs(self.bin)
        # a `gh` that serves the body of PR N from bodies/N: the only call the workflow makes is `gh api repos/R/pulls/N --jq ...`
        gh = os.path.join(self.bin, 'gh')
        with open(gh, 'w') as f:
            f.write('#!/bin/bash\n[[ "$1" == api && "$2" =~ ^repos/[^/]+/[^/]+/pulls/([0-9]+)$ ]] || { echo "gh shim: unexpected $*" >&2; exit 9; }\n'
                    'cat "$GH_BODIES/${BASH_REMATCH[1]}" 2>/dev/null || exit 1\n')
        os.chmod(gh, 0o755)
        self.bodies = os.path.join(self.d, '.bodies')
        os.makedirs(self.bodies)

    def g(self, *args):
        p = subprocess.run(['git', '-C', self.d] + list(args), capture_output=True, text=True, env=GIT_ENV)
        assert p.returncode == 0, p.stderr
        return p.stdout.strip()

    def write(self, rel, text):
        p = os.path.join(self.d, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, 'w') as f:
            f.write(text)

    def commit(self, msg):
        self.g('add', '-A', '--', '.', ':!.bin', ':!.bodies', ':!.runner')
        self.g('commit', '-q', '-m', msg)
        return self.g('rev-parse', 'HEAD')

    def pr_body(self, n, text):
        with open(os.path.join(self.bodies, str(n)), 'w') as f:
            f.write(text)

    def sh(self, script, **env):
        runner = os.path.join(self.d, '.runner')
        os.makedirs(runner, exist_ok=True)
        e = dict(GIT_ENV, PATH=self.bin + os.pathsep + os.environ['PATH'], GH_BODIES=self.bodies, RUNNER_TEMP=runner,
                 GITHUB_ENV=os.path.join(runner, 'env'), GH_REPO='o/r', **env)
        p = subprocess.run(['bash', '-c', script], cwd=self.d, capture_output=True, text=True, env=e)
        return p.returncode, p.stdout + p.stderr


class MergeGroupDryRun(unittest.TestCase):
    """The merge_group path of fragment-touched, executed: the step's own shell text, with a `gh` shim, over a scratch repo
    whose history is the queue's (one squash commit per PR, subject `... (#N)`)."""
    STEP = 'Code under a tla fragment changed without its fragment'

    def setUp(self):
        self.r = Scratch()
        self.addCleanup(shutil.rmtree, self.r.d)
        self.script = step_run(jobs(read(PR_YML))['fragment-touched'], self.STEP)
        self.r.write('A.java', JAVA)
        self.base = self.r.commit('init')

    def merge_group(self, base=None, **env):
        return self.r.sh(self.script, EVENT='merge_group', BASE_REV=base or self.base, TLA_INST=REPO, TLA_HEAD_OWN='0', PR_BODY='', **env)

    def test_a_queued_pr_with_code_under_a_fragment_and_no_excuse_is_red(self):
        self.r.write('A.java', IN_REGION)
        self.r.commit('fix: a (#11)')
        self.r.pr_body(11, 'nothing to see')
        rc, out = self.merge_group()
        self.assertEqual(rc, 1, out)
        self.assertIn('queued', out)
        self.assertIn('is PR #11', out)
        self.assertIn('RED M:Step', out)

    def test_the_excuse_is_read_from_the_body_of_the_pr_the_commit_names(self):
        self.r.write('A.java', IN_REGION)
        self.r.commit('fix: a (#11)')
        self.r.pr_body(11, 'text\ntla-unchanged: M: the constant is not modelled\n')
        rc, out = self.merge_group()
        self.assertEqual(rc, 0, out)
        self.assertIn('excused by tla-unchanged: the constant is not modelled', out)

    def test_an_excuse_in_another_queued_prs_body_does_not_excuse_this_commit(self):
        self.r.write('A.java', IN_REGION)
        self.r.commit('fix: a (#11)')
        self.r.write('A.java', IN_REGION.replace('return 9;', 'return 10;'))
        self.r.commit('fix: b (#12)')
        self.r.pr_body(11, 'no excuse')
        self.r.pr_body(12, 'tla-unchanged: M: mine')
        rc, out = self.merge_group()
        self.assertEqual(rc, 1, out)

    def test_each_commit_is_judged_on_its_own_patch_not_on_the_groups_sum(self):
        # PR 11 changes code under the fragment and leaves it; PR 12, queued behind it, changes the fragment. The sum of the
        # two (base..head) shows a changed fragment and would pass; PR 11 alone is the violation.
        self.r.write('A.java', IN_REGION)
        self.r.commit('fix: a (#11)')
        self.r.write('A.java', NEW_FRAG)
        self.r.commit('fix: b (#12)')
        self.r.pr_body(11, 'x')
        self.r.pr_body(12, 'x')
        rc, out = self.merge_group()
        self.assertEqual(rc, 1, out)
        self.assertIn('RED M:Step', out)

    def test_a_commit_that_names_no_pr_is_judged_without_an_excuse_and_says_so(self):
        self.r.write('A.java', IN_REGION)
        self.r.commit('fix: a')
        rc, out = self.merge_group()
        self.assertEqual(rc, 1, out)
        self.assertIn('names no PR in its subject', out)

    def test_a_group_whose_changes_are_outside_every_region_is_green_with_its_count(self):
        self.r.write('A.java', OUTSIDE)
        self.r.commit('fix: a (#11)')
        self.r.pr_body(11, '')
        rc, out = self.merge_group()
        self.assertEqual(rc, 0, out)
        self.assertIn('GREEN 0 unexcused, 0 accounted for', out)

    def test_an_unreadable_pr_body_is_not_a_pass(self):
        self.r.write('A.java', OUTSIDE)
        self.r.commit('fix: a (#99)')            # no body file: the shim fails like an API error
        rc, out = self.merge_group()
        self.assertNotEqual(rc, 0, out)

    def test_an_empty_group_examined_nothing_and_fails(self):
        rc, out = self.merge_group(base='HEAD')
        self.assertEqual(rc, 2, out)
        self.assertIn('nothing was examined', out)

    def test_the_pull_request_event_still_reads_the_body_it_was_given(self):
        self.r.write('A.java', IN_REGION)
        self.r.commit('fix: a')
        for body, rc_want in (('', 1), ('tla-unchanged: M: reason', 0)):
            rc, out = self.r.sh(self.script, EVENT='pull_request', BASE_REV=self.base, TLA_INST=REPO, TLA_HEAD_OWN='0', PR_BODY=body)
            self.assertEqual(rc, rc_want, out)


class InstrumentFromBase(unittest.TestCase):
    """The #3605 case: a branch older than the instrument. Its own tools/tla lacks test_touched.py (`NO TESTS RAN`, exit 5);
    the instrument step reads the base's, which judges it."""
    JOB = 'fragment-touched'

    def setUp(self):
        self.r = Scratch()
        self.addCleanup(shutil.rmtree, self.r.d)
        self.job = jobs(read(PR_YML))[self.JOB]
        self.step = step_run(self.job, 'Instrument from the base')
        # the old state: tools/tla without test_touched.py; the base adds it
        shutil.copytree(HERE, os.path.join(self.r.d, 'tools', 'tla'), ignore=shutil.ignore_patterns('__pycache__', 'test_touched.py'))
        self.r.write('A.java', JAVA)
        self.old = self.r.commit('old tree')
        shutil.copy(os.path.join(HERE, 'test_touched.py'), os.path.join(self.r.d, 'tools', 'tla', 'test_touched.py'))
        self.base = self.r.commit('base gains the self-test (#3593)')
        self.r.g('checkout', '-q', '-b', 'pr', self.old)
        self.r.write('A.java', OUTSIDE)
        self.head = self.r.commit('pr: a change outside every region')

    def instrument(self, need=None, base=None):
        return self.r.sh(self.step, BASE_REV=base or self.base, NEED=need or 'touched.py extract.py test_touched.py')

    def test_control_the_old_branchs_own_self_test_cannot_run(self):
        rc, out = self.r.sh("python3 -m unittest discover -s tools/tla -p 'test_touched.py'")
        self.assertEqual(rc, 5, out)
        self.assertIn('NO TESTS RAN', out)

    def test_the_base_instrument_is_found_and_the_branch_is_judged(self):
        rc, out = self.instrument()
        self.assertEqual(rc, 0, out)
        env = read_env(self.r)
        self.assertEqual(env['TLA_HEAD_OWN'], '0', 'the branch edits no tools/tla script')
        inst = env['TLA_INST']
        rc, out = self.r.sh(f"python3 -m unittest discover -s '{inst}/tools/tla' -p 'test_touched.py' 2>&1 | tail -3")
        self.assertIn('OK', out)
        rc, out = self.r.sh(f"python3 '{inst}/tools/tla/touched.py' --base {self.base} --head HEAD --pr-body-file /dev/null")
        self.assertEqual(rc, 0, out)
        self.assertIn('GREEN 0 unexcused, 0 accounted for', out)

    def test_an_instrument_the_base_lacks_is_one_named_line_not_exit_5_or_a_pass(self):
        rc, out = self.instrument(base=self.old)
        self.assertEqual(rc, 1, out)
        self.assertEqual([l for l in out.splitlines() if l.startswith('INSTRUMENT ABSENT:')],
                         [f'INSTRUMENT ABSENT: tools/tla/test_touched.py in {self.old}; the base itself lacks it, no judgement is possible'])

    def test_a_change_that_edits_a_tools_tla_script_also_runs_the_heads_copy(self):
        self.r.write('tools/tla/touched.py', read(os.path.join(HERE, 'touched.py')) + '\n# edited\n')
        self.r.commit('pr: edits the judge')
        rc, out = self.instrument()
        self.assertEqual(rc, 0, out)
        env = read_env(self.r)
        self.assertEqual(env['TLA_HEAD_OWN'], '1')

    def test_the_judge_that_runs_is_the_bases_when_the_pr_weakens_its_own(self):
        # the branch rewrites touched.py to approve everything; the base's copy, which the step extracts, still goes red
        self.r.write('A.java', IN_REGION)
        self.r.write('tools/tla/touched.py', '#!/usr/bin/env python3\nimport sys\nprint("touched: GREEN"); sys.exit(0)\n')
        self.r.commit('pr: code under a fragment, and a judge that says green')
        rc, out = self.instrument()
        self.assertEqual(rc, 0, out)
        inst = read_env(self.r)['TLA_INST']
        rc, out = self.r.sh(f"python3 '{inst}/tools/tla/touched.py' --base {self.base} --head HEAD --pr-body-file /dev/null")
        self.assertEqual(rc, 1, out)
        self.assertIn('RED M:Step', out)


class OneProfileList(unittest.TestCase):
    def run_sh_profiles(self):
        m = re.search(r'^\[\[ (\$profile == \w+(?: \|\| \$profile == \w+)*) \]\] \|\| \{ echo "run: ERROR profile must be', read(os.path.join(HERE, 'run.sh')), re.M)
        self.assertIsNotNone(m, 'run.sh profile validation line not found')
        return set(re.findall(r'\$profile == (\w+)', m.group(1)))

    def test_run_sh_accepts_the_four_profiles(self):
        self.assertEqual(self.run_sh_profiles(), {'small', 'full', 'sim', 'heavy'})

    def test_results_check_accepts_exactly_the_profiles_run_sh_writes(self):
        rc = load('results_check', 'results-check.py')
        for p in self.run_sh_profiles():
            self.assertRegex(p, rc.VALID['profile'])
        for p in ('huge', 'Small', '', 'small ', 'heavy2'):
            self.assertNotRegex(p, rc.VALID['profile'])

    def test_plan_matrix_names_exactly_those_profiles(self):
        self.assertEqual(set(load('plan_matrix', 'plan-matrix.py').PROFILES), self.run_sh_profiles())

    def test_the_dispatch_input_offers_exactly_those_profiles(self):
        m = re.search(r'profile:\n(?:        .*\n)*?        options:\n((?:          - \w+\n)+)', read(MODELS_YML))
        self.assertIsNotNone(m)
        self.assertEqual(set(re.findall(r'- (\w+)', m.group(1))), self.run_sh_profiles())

    def test_the_run_sh_usage_line_names_exactly_those_profiles(self):
        m = re.search(r'^# Usage: tools/tla/run\.sh \[([a-z|]+)\]', read(os.path.join(HERE, 'run.sh')), re.M)
        self.assertIsNotNone(m)
        self.assertEqual(set(m.group(1).split('|')), self.run_sh_profiles())


if __name__ == '__main__':
    unittest.main()
