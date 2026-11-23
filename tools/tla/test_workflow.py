"""Tests of the TLA+ workflows' wiring, read as text (no YAML library: the runners and a laptop both have only python3).

  - the jar: every job that uses tla2tools.jar downloads it from the owner-approved asset of this repository, verifies the
    sha256 pin on a cache hit too, and keys its cache on the sha; the workflow, the README and the tests agree on the pin (F1);
  - the pull-request job of the paths-list check exists and runs the check (F7);
  - the list of profiles is one list: run.sh's validation line is the source, and results-check.py, plan-matrix.py, the
    workflow's dispatch input and the README's usage line must name exactly those profiles (the heavy profile once wrote
    rows that results-check.py called malformed).
"""
import importlib.util
import os
import re
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
