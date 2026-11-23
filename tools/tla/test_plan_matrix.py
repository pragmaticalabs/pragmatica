"""Tests of plan-matrix.py: the shards of a models run, one job per (model, profile)."""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, 'plan-matrix.py')

ROWS = (
    '# comment\n'
    'Alpha\trootA\tcode\tpass\t-\t-\tw\n'
    'Alpha\trootA\tbad\tfail\tP\t-\tw\n'
    'Beta\trootB\tcode\tpass\t-\tK=2\tw\n'
    'Beta\trootB\tbig\tpass\t-\theavy\tw\n'
    'Gamma\trootC\tbig\tpass\t-\theavy\tw\n'
)


def plan(models_text, *args):
    with tempfile.NamedTemporaryFile('w', suffix='.tsv', delete=False) as f:
        f.write(models_text)
    try:
        p = subprocess.run([sys.executable, SCRIPT, '--models', f.name] + list(args), capture_output=True, text=True)
    finally:
        os.unlink(f.name)
    return p.returncode, p.stdout, p.stderr


def shards(models_text, *args):
    rc, out, err = plan(models_text, *args)
    assert rc == 0, err
    return [(s['module'], s['profile']) for s in json.loads(out)['include']]


class PlanMatrix(unittest.TestCase):
    def test_push_is_small_for_every_model_and_never_heavy(self):
        self.assertEqual(shards(ROWS, '--event', 'push'), [('Alpha', 'small'), ('Beta', 'small'), ('Gamma', 'small')])

    def test_schedule_adds_a_heavy_shard_only_for_models_with_a_heavy_row(self):
        self.assertEqual(shards(ROWS, '--event', 'schedule'),
                         [('Alpha', 'small'), ('Beta', 'small'), ('Gamma', 'small'), ('Beta', 'heavy'), ('Gamma', 'heavy')])

    def test_dispatch_heavy_runs_only_models_with_a_heavy_row(self):
        self.assertEqual(shards(ROWS, '--event', 'workflow_dispatch', '--profile', 'heavy'), [('Beta', 'heavy'), ('Gamma', 'heavy')])

    def test_dispatch_full_and_sim_cover_every_matching_model(self):
        self.assertEqual(shards(ROWS, '--event', 'workflow_dispatch', '--profile', 'full', '--modules', 'Alpha|Beta'),
                         [('Alpha', 'full'), ('Beta', 'full')])
        self.assertEqual(shards(ROWS, '--event', 'workflow_dispatch', '--profile', 'sim', '--modules', 'Gamma'), [('Gamma', 'sim')])

    def test_the_regex_is_anchored_like_run_sh(self):
        self.assertEqual(shards(ROWS, '--event', 'push', '--modules', 'Alpha'), [('Alpha', 'small')])
        rc, _, err = plan(ROWS, '--event', 'push', '--modules', 'Alph')
        self.assertEqual(rc, 2, err)

    def test_a_shard_carries_its_root_and_budgets(self):
        rc, out, _ = plan(ROWS, '--event', 'schedule')
        by = {(s['module'], s['profile']): s for s in json.loads(out)['include']}
        self.assertEqual(by[('Beta', 'small')], {'module': 'Beta', 'root': 'rootB', 'profile': 'small', 'job_minutes': 150, 'tlc_timeout': 1200})
        self.assertEqual(by[('Beta', 'heavy')]['tlc_timeout'], 18000)
        self.assertGreater(by[('Beta', 'heavy')]['job_minutes'], by[('Beta', 'heavy')]['tlc_timeout'] // 60)

    def test_no_matching_model_is_an_error_not_an_empty_run(self):
        rc, out, err = plan(ROWS, '--event', 'push', '--modules', 'Nope')
        self.assertEqual((rc, out), (2, ''), err)
        rc, out, err = plan(ROWS, '--event', 'workflow_dispatch', '--profile', 'heavy', '--modules', 'Alpha')
        self.assertEqual(rc, 2, err)
        self.assertIn('no shard to run', err)

    def test_unknown_event_and_profile_are_errors(self):
        self.assertEqual(plan(ROWS, '--event', 'pull_request')[0], 2)
        self.assertEqual(plan(ROWS, '--event', 'push', '--profile', 'huge')[0], 2)

    def test_a_row_with_too_few_columns_is_an_error(self):
        rc, _, err = plan('Alpha\trootA\tcode\tpass\n', '--event', 'push')
        self.assertEqual(rc, 2, err)

    def test_real_models_tsv_every_module_is_a_small_shard_and_every_heavy_row_has_a_heavy_shard(self):
        real = os.path.join(HERE, 'models.tsv')
        rows = [l.split('\t') for l in open(real) if l.strip() and not l.lstrip().startswith('#')]
        modules = []
        for c in rows:
            if c[0] not in modules:
                modules.append(c[0])
        heavy = [m for m in modules if any(c[0] == m and c[5] == 'heavy' for c in rows)]
        p = subprocess.run([sys.executable, SCRIPT, '--event', 'schedule'], capture_output=True, text=True)
        got = [(s['module'], s['profile']) for s in json.loads(p.stdout)['include']]
        self.assertEqual([m for m, pr in got if pr == 'small'], modules)
        self.assertEqual([m for m, pr in got if pr == 'heavy'], heavy)
        self.assertTrue(heavy, 'models.tsv has no heavy row: the heavy shard path is untested against the real table')


if __name__ == '__main__':
    unittest.main()
