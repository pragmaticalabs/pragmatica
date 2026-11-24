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


BUDGETS = (
    '*\t*\t18000\t-\tdefault\n'
    '@hosted\tlimit\t18000\t12g\tlimit\n'
)


def plan(models_text, *args, budgets=BUDGETS, shards_text=None):
    made = []
    try:
        for text in (models_text, budgets) + ((shards_text,) if shards_text is not None else ()):
            with tempfile.NamedTemporaryFile('w', suffix='.tsv', delete=False) as f:
                f.write(text)
            made.append(f.name)
        extra = ['--shards', made[2]] if shards_text is not None else []
        p = subprocess.run([sys.executable, SCRIPT, '--models', made[0], '--budgets', made[1]] + extra + list(args), capture_output=True, text=True)
    finally:
        for name in made:
            os.unlink(name)
    return p.returncode, p.stdout, p.stderr


def shards(models_text, *args, budgets=BUDGETS):
    rc, out, err = plan(models_text, *args, budgets=budgets)
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
        self.assertEqual(by[('Beta', 'small')], {'module': 'Beta', 'root': 'rootB', 'profile': 'small', 'job_minutes': 150,
                                                 'tlc_timeout': 1200, 'skipped_host': '', 'shard': '', 'variants': '.*'})
        # full and heavy: each row runs under its own budget (budgets.tsv), so the matrix carries no per-config cap
        self.assertEqual(by[('Beta', 'heavy')]['tlc_timeout'], '')
        self.assertEqual(by[('Beta', 'heavy')]['job_minutes'], 350)

    # --- shards of the small profile (#3604): a module whose small rows do not fit one job is split by shards.tsv ---
    SHARDS = 'Beta\tone\tcode\nBeta\ttwo\tbig\n'

    def sharded(self, shards_text, *args):
        rc, out, err = plan(ROWS, *args, shards_text=shards_text)
        return rc, (json.loads(out)['include'] if rc == 0 else out), err

    def test_a_sharded_module_is_one_small_job_per_shard_carrying_its_variant_regex(self):
        rc, inc, err = self.sharded(self.SHARDS, '--event', 'push')
        self.assertEqual(rc, 0, err)
        self.assertEqual([(s['module'], s['shard'], s['variants']) for s in inc],
                         [('Alpha', '', '.*'), ('Beta', 'one', 'code'), ('Beta', 'two', 'big'), ('Gamma', '', '.*')])

    def test_shards_apply_to_the_small_profile_only(self):
        rc, inc, err = self.sharded(self.SHARDS, '--event', 'workflow_dispatch', '--profile', 'full', '--modules', 'Beta')
        self.assertEqual((rc, [(s['shard'], s['variants']) for s in inc]), (0, [('', '.*')]), err)
        rc, inc, err = self.sharded(self.SHARDS, '--event', 'workflow_dispatch', '--profile', 'heavy', '--modules', 'Beta')
        self.assertEqual((rc, [(s['shard'], s['variants']) for s in inc]), (0, [('', '.*')]), err)

    def test_the_shards_must_partition_the_rows_of_the_module(self):
        for bad, why in (('Beta\tone\tcode\n', 'Beta/big is in 0 shards'),                              # a row in no shard is dropped
                         ('Beta\tone\tcode|big\nBeta\ttwo\tbig\n', 'Beta/big is in 2 shards'),        # a row in two shards runs twice
                         ('Beta\tone\tcode|big\nBeta\ttwo\tnothing\n', 'shard two matches no row'),
                         ('Nope\tone\t.*\n', 'no such module in models.tsv'),
                         ('Beta\tone\tcode\nBeta\tone\tbig\n', 'second shard named one'),
                         ('Beta\tone\tcode\n', 'is in 0 shards')):
            rc, out, err = self.sharded(bad, '--event', 'push')
            self.assertEqual(rc, 2, (bad, out))
            self.assertIn(why, err)

    def test_a_shard_regex_or_name_that_awk_and_python_could_read_differently_is_refused(self):
        for bad, why in (('Beta\tone\t(?:code)\nBeta\ttwo\tbig\n', 'may use only'),
                         ('Beta\tone\tcode\\d?\nBeta\ttwo\tbig\n', 'may use only'),
                         ('Beta\tone/x\tcode\nBeta\ttwo\tbig\n', 'shard name'),
                         ('Beta\tone\n', 'row with 2 columns')):
            rc, out, err = self.sharded(bad, '--event', 'push')
            self.assertEqual(rc, 2, (bad, out))
            self.assertIn(why, err)

    def test_a_shards_file_that_cannot_be_read_is_an_error_not_an_unsharded_run(self):
        p = subprocess.run([sys.executable, SCRIPT, '--shards', '/nonexistent/shards.tsv', '--event', 'push'], capture_output=True, text=True)
        self.assertEqual(p.returncode, 2, p.stdout)

    def test_the_real_shards_partition_the_real_table_and_awk_selects_what_python_does(self):
        # run.sh selects a shard's rows with awk (`$3 ~ "^(" f ")$"`); the plan validated the partition with Python: they must agree
        with open(os.path.join(HERE, 'models.tsv')) as f:
            real = [l.rstrip('\n').split('\t') for l in f if l.strip() and not l.lstrip().startswith('#')]
        p = subprocess.run([sys.executable, SCRIPT, '--event', 'push'], capture_output=True, text=True)
        self.assertEqual(p.returncode, 0, p.stderr)
        by_module = {}
        for sh in json.loads(p.stdout)['include']:
            by_module.setdefault(sh['module'], []).append(sh)
        for module, shs in by_module.items():
            variants = [c[2] for c in real if c[0] == module]
            picked = []
            for sh in shs:
                out = subprocess.run(['awk', '-F\t', '-v', f'f={sh["variants"]}', '-v', f'm={module}', '$1 == m && $3 ~ "^(" f ")$" { print $3 }',
                                      os.path.join(HERE, 'models.tsv')], capture_output=True, text=True).stdout.split()
                self.assertTrue(out, (module, sh['shard']))
                picked += out
            self.assertEqual(sorted(picked), sorted(variants), module)   # every row in exactly one shard, as awk reads it

    # --- per-row budgets (#3636): a row over the hosted limit is named SKIPPED-HOST in the plan, never a pass, never dropped ---
    OVER = BUDGETS + 'Beta\tbig\t21600\t8g\tover the time limit\n' + 'Alpha\tbad\t600\t20g\tover the heap limit\n'

    def skipped(self, *args, budgets=OVER):
        rc, out, err = plan(ROWS, *args, budgets=budgets)
        self.assertEqual(rc, 0, err)
        return {(s['module'], s['profile']): s['skipped_host'] for s in json.loads(out)['include']}, err

    def test_a_row_over_the_hosted_time_or_heap_limit_is_named_in_its_full_shard_and_on_stderr(self):
        by, err = self.skipped('--event', 'workflow_dispatch', '--profile', 'full')
        self.assertEqual(by[('Alpha', 'full')], 'Alpha/bad budget 600s/20g')   # heap alone is enough
        self.assertEqual(by[('Beta', 'full')], 'Beta/big budget 21600s/8g')   # time alone is enough
        self.assertEqual(by[('Gamma', 'full')], '')
        self.assertIn('plan-matrix: SKIPPED-HOST Alpha/bad budget 600s/20g (profile full)', err)
        self.assertIn('plan-matrix: SKIPPED-HOST Beta/big budget 21600s/8g (profile full)', err)
        self.assertEqual(err.count('SKIPPED-HOST'), 2)   # counted: exactly the two rows over the limit, no more

    def test_a_row_exactly_at_the_hosted_limit_runs_on_the_runner(self):
        at = BUDGETS + 'Beta\tbig\t18000\t12g\tat the limit\n'
        by, err = self.skipped('--event', 'workflow_dispatch', '--profile', 'full', budgets=at)
        self.assertEqual(set(by.values()), {''}, err)
        self.assertNotIn('SKIPPED-HOST', err)

    def test_heavy_names_only_the_heavy_rows_and_small_and_sim_name_none(self):
        by, err = self.skipped('--event', 'workflow_dispatch', '--profile', 'heavy')
        self.assertEqual(by, {('Beta', 'heavy'): 'Beta/big budget 21600s/8g', ('Gamma', 'heavy'): ''})   # Alpha/bad is not a heavy row
        for prof in ('small', 'sim'):
            by, err = self.skipped('--event', 'workflow_dispatch', '--profile', prof)
            self.assertEqual(set(by.values()), {''})
            self.assertNotIn('SKIPPED-HOST', err)

    def test_the_default_row_applies_to_a_row_without_its_own(self):
        slow_default = '*\t*\t30000\t-\tdefault over the limit\n@hosted\tlimit\t18000\t12g\tlimit\n'
        by, _ = self.skipped('--event', 'workflow_dispatch', '--profile', 'full', '--modules', 'Gamma', budgets=slow_default)
        self.assertEqual(by[('Gamma', 'full')], 'Gamma/big budget 30000s/0g')

    def test_a_budget_table_that_names_a_missing_row_or_lacks_a_required_row_is_an_error(self):
        for bad, why in ((BUDGETS + 'Nope\tcode\t600\t-\tx\n', 'no such row in models.tsv'),
                         (BUDGETS + 'Alpha\tcode\t600\t-\tx\nAlpha\tcode\t700\t-\ty\n', 'second row Alpha/code'),
                         (BUDGETS + 'Alpha\tcode\t0\t-\tx\n', 'positive whole number'),
                         (BUDGETS + 'Alpha\tcode\t600\t6\tx\n', 'heap must be'),
                         (BUDGETS + 'Alpha\tcode\t600\t-\n', 'row with 4 columns'),
                         (BUDGETS + 'Alpha\tcode\t600\t-\tx\textra\n', 'row with 6 columns'),
                         ('@hosted\tlimit\t18000\t12g\tlimit\n', 'needs a `*` default row'),
                         ('*\t*\t18000\t-\tdefault\n', 'needs a `*` default row and an @hosted row')):
            rc, out, err = plan(ROWS, '--event', 'push', budgets=bad)
            self.assertEqual((rc, out), (2, ''), err)
            self.assertIn(why, err)

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

    def test_a_row_one_column_short_of_the_seven_is_an_error_and_seven_is_not(self):
        # models.tsv columns: module root variant expect property small what; run.sh reads all seven
        rc, _, err = plan('Alpha\trootA\tcode\tpass\t-\t-\n', '--event', 'push')
        self.assertEqual(rc, 2, err)
        self.assertIn('row with 6 columns', err)
        self.assertEqual(plan('Alpha\trootA\tcode\tpass\t-\t-\twhat\n', '--event', 'push')[0], 0)

    def test_a_module_with_two_source_roots_is_an_error_naming_the_module(self):
        # run.sh takes the first row's root for the whole module; a second root would be ignored, so it is refused
        rc, out, err = plan('Alpha\trootA\tcode\tpass\t-\t-\tw\nAlpha\trootB\tbad\tfail\tP\t-\tw\n', '--event', 'push')
        self.assertEqual((rc, out), (2, ''), err)
        self.assertIn('module Alpha has two source roots', err)

    def test_real_models_tsv_every_module_is_a_small_shard_and_every_heavy_row_has_a_heavy_shard(self):
        real = os.path.join(HERE, 'models.tsv')
        with open(real) as f:
            rows = [l.split('\t') for l in f if l.strip() and not l.lstrip().startswith('#')]
        modules = []
        for c in rows:
            if c[0] not in modules:
                modules.append(c[0])
        heavy = [m for m in modules if any(c[0] == m and c[5] == 'heavy' for c in rows)]
        p = subprocess.run([sys.executable, SCRIPT, '--event', 'schedule'], capture_output=True, text=True)
        got = [(s['module'], s['profile']) for s in json.loads(p.stdout)['include']]
        with open(os.path.join(HERE, 'shards.tsv')) as f:
            nshards = [l.split('\t')[0] for l in f if l.strip() and not l.lstrip().startswith('#')]
        expected = [m for m in modules for _ in range(max(1, nshards.count(m)))]   # a sharded module is one small job per shards.tsv row
        self.assertEqual([m for m, pr in got if pr == 'small'], expected)
        self.assertEqual([m for m, pr in got if pr == 'heavy'], heavy)
        self.assertTrue(heavy, 'models.tsv has no heavy row: the heavy shard path is untested against the real table')

    def test_real_tables_name_exactly_the_rows_run_sh_lists_as_over_the_hosted_limit(self):
        # two implementations (plan-matrix.py, run.sh TLA_HOST=list) must agree on the real tables, for both full and heavy
        real = {}
        for prof in ('full', 'heavy'):
            p = subprocess.run([sys.executable, SCRIPT, '--event', 'workflow_dispatch', '--profile', prof], capture_output=True, text=True)
            self.assertEqual(p.returncode, 0, p.stderr)
            named = set()
            for s in json.loads(p.stdout)['include']:
                named |= {n.split(' budget ')[0] for n in s['skipped_host'].split(', ') if n}
            listed = subprocess.run(['bash', os.path.join(HERE, 'run.sh'), prof], capture_output=True, text=True, cwd=os.path.join(HERE, '..', '..'),
                                    env=dict(os.environ, TLA_HOST='list'))
            self.assertEqual(listed.returncode, 0, listed.stderr)
            self.assertEqual(named, {l.split('\t')[0] for l in listed.stdout.splitlines()}, prof)
            real[prof] = named
        self.assertIn('SharedPools/live', real['full'], 'the seeded budgets.tsv must put SharedPools/live over the hosted limit')
        self.assertTrue({'Fsm2Leader/code_nolate_close', 'Fsm2Leader/code_nolate_show', 'Fsm2Leader/code_nolate_persist'} <= real['full'])

    def test_the_over_limit_rule_run_sh_asks_is_the_one_plan_uses_at_and_over_each_edge(self):
        # run.sh has no rule of its own: it calls `plan-matrix.py --over-limit` (rc 0 over, 1 within, 2 unusable input).
        def asked(t, h, lt='18000', lh='12g'):
            return subprocess.run([sys.executable, SCRIPT, '--over-limit', t, h, lt, lh], capture_output=True, text=True).returncode
        self.assertEqual(
            [asked('18000', '12g'), asked('18001', '12g'), asked('18000', '13g'), asked('600', '-'), asked('18001', '-'), asked('1', '1g', '1', '1g')],
            [1, 0, 0, 1, 0, 1])
        self.assertEqual(asked('x', '8g'), 2)
        # and plan() reaches the same answers through the same function
        for t, h, over in ((18000, 12, False), (18001, 12, True), (18000, 13, True), (5, 0, False)):
            self.assertEqual(plan_skips(t, h), over, (t, h))


def plan_skips(t, h):
    import importlib.util
    spec = importlib.util.spec_from_file_location('plan_matrix', SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.over_hosted((t, h), (18000, 12))


if __name__ == '__main__':
    unittest.main()
