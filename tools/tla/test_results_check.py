"""Tests of results-check.py over fixture repositories: a row whose commit is not an ancestor of the PR head is rejected."""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, 'results-check.py')
sys.path.insert(0, HERE)

HEADER = 'commit\tdirty\tdate\tprofile\tmodule\tvariant\texpect\tactual\tviolated\tdistinct\ttlc\tverdict\tconstants'
ENV = dict(os.environ, GIT_AUTHOR_NAME='t', GIT_AUTHOR_EMAIL='t@t', GIT_COMMITTER_NAME='t', GIT_COMMITTER_EMAIL='t@t',
           GIT_CONFIG_GLOBAL='/dev/null', GIT_CONFIG_SYSTEM='/dev/null')
F = 'tools/tla/results.tsv'


def row(commit, dirty='clean', module='Fix', variant='ok', expect='pass', actual='pass', violated='', distinct='4', verdict='OK', consts='K=2',
        profile='small'):
    return '\t'.join([commit, dirty, '2026-11-22T20:00:00Z', profile, module, variant, expect, actual, violated, distinct, '2.20', verdict, consts])


class ResultsCheck(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.d)
        self.g('init', '-q', '-b', 'main')
        self.n = 0

    def g(self, *a):
        p = subprocess.run(['git', '-C', self.d] + list(a), capture_output=True, text=True, env=ENV)
        assert p.returncode == 0, p.stderr
        return p.stdout.strip()

    def commit(self, text=None, msg='c'):
        self.n += 1
        with open(os.path.join(self.d, f'f{self.n}.txt'), 'w') as f:
            f.write(str(self.n))
        if text is not None:
            os.makedirs(os.path.join(self.d, 'tools/tla'), exist_ok=True)
            with open(os.path.join(self.d, F), 'w') as f:
                f.write(text)
        self.g('add', '-A')
        self.g('commit', '-q', '-m', msg)
        return self.g('rev-parse', 'HEAD')

    def run_check(self, *extra):
        p = subprocess.run([sys.executable, SCRIPT, '--repo', self.d] + list(extra), capture_output=True, text=True)
        return p.returncode, p.stdout + p.stderr

    def table(self, *rows):
        return HEADER + '\n' + ''.join(r + '\n' for r in rows)

    def test_row_of_an_ancestor_commit_is_green(self):
        c0 = self.commit()
        self.commit(self.table(row(c0)))
        rc, out = self.run_check()
        self.assertEqual(rc, 0, out)
        self.assertIn('GREEN 0 bad of 1 rows', out)

    def test_row_of_a_commit_on_another_branch_is_red(self):
        self.commit()
        self.g('checkout', '-q', '-b', 'other')
        other = self.commit(msg='only on other')
        self.g('checkout', '-q', 'main')
        self.commit(self.table(row(other)))
        rc, out = self.run_check()
        self.assertEqual(rc, 1, out)
        self.assertIn(f'tools/tla/results.tsv:2: RED Fix/ok: commit {other[:10]} is not an ancestor of', out)

    def test_row_of_a_descendant_of_head_is_red(self):
        c0 = self.commit()
        c1 = self.commit()
        self.g('checkout', '-q', '-b', 'earlier', c0)
        self.commit(self.table(row(c1)), msg='rows name a commit that is not behind this head')
        rc, out = self.run_check()
        self.assertEqual(rc, 1, out)
        self.assertIn(f'commit {c1[:10]} is not an ancestor of', out)

    def test_row_of_a_commit_not_in_the_repository_is_red(self):
        self.commit(self.table(row('0123456789abcdef0123456789abcdef01234567')))
        rc, out = self.run_check()
        self.assertEqual(rc, 1, out)
        self.assertIn('is not a commit of this repository', out)

    def test_dirty_tree_row_is_red(self):
        c0 = self.commit()
        self.commit(self.table(row(c0, dirty='dirty')))
        rc, out = self.run_check()
        self.assertEqual(rc, 1, out)
        self.assertIn('the run was on a dirty tree', out)

    def test_row_made_outside_git_is_red(self):
        self.commit(self.table(row('none', dirty='unknown')))
        rc, out = self.run_check()
        self.assertEqual(rc, 1, out)
        self.assertIn('outside a git repository', out)

    def test_wrong_header_is_red(self):
        self.commit('commit\tmodule\n')
        rc, out = self.run_check()
        self.assertEqual(rc, 1, out)
        self.assertIn('header is not the evidence-row header', out)

    def test_short_row_is_red(self):
        c0 = self.commit()
        self.commit(self.table(row(c0)[:-8]))
        rc, out = self.run_check()
        self.assertEqual(rc, 1, out)
        self.assertIn('columns, expected 13', out)

    def test_malformed_values_are_red_and_named(self):
        c0 = self.commit()
        self.commit(self.table(row(c0, verdict='GREEN'), row(c0, distinct='4,000'), row(c0[:10])))
        rc, out = self.run_check()
        self.assertEqual(rc, 1, out)
        self.assertIn('malformed value in column verdict', out)
        self.assertIn('malformed value in column distinct', out)
        self.assertIn('malformed value in column commit', out)

    def test_fail_row_with_violated_property_and_empty_trailing_constants_is_green(self):
        c0 = self.commit()
        self.commit(self.table(row(c0, variant='bad', expect='fail', actual='fail', violated='Small', consts='')))
        rc, out = self.run_check()
        self.assertEqual(rc, 0, out)

    def test_only_rows_added_by_the_pr_are_checked_with_base(self):
        c0 = self.commit()
        old = row('0123456789abcdef0123456789abcdef01234567')    # a row merged earlier whose commit was squashed away
        base = self.commit(self.table(old))
        self.g('checkout', '-q', '-b', 'pr')
        self.commit(self.table(old, row(c0, variant='second')))
        rc, out = self.run_check('--base', 'main', '--head', 'pr')
        self.assertEqual(rc, 0, out)
        self.assertIn('GREEN 0 bad of 1 rows', out)
        rc, out = self.run_check('--head', 'pr')                  # without --base the old row is checked too
        self.assertEqual(rc, 1, out)
        self.assertEqual(self.g('rev-parse', 'main'), base)

    def test_bad_row_added_by_the_pr_is_red_with_base(self):
        c0 = self.commit(self.table())
        self.g('checkout', '-q', '-b', 'other')
        other = self.commit(msg='elsewhere')
        self.g('checkout', '-q', 'main')
        self.g('checkout', '-q', '-b', 'pr')
        self.commit(self.table(row(other)))
        rc, out = self.run_check('--base', 'main', '--head', 'pr')
        self.assertEqual(rc, 1, out)
        self.assertIn('is not an ancestor of', out)
        self.assertEqual(self.g('merge-base', 'main', 'pr'), c0)

    def test_pr_without_a_results_file_is_green_with_base_and_an_error_without(self):
        self.commit()
        self.g('checkout', '-q', '-b', 'pr')
        self.commit()
        rc, out = self.run_check('--base', 'main', '--head', 'pr')
        self.assertEqual(rc, 0, out)
        rc, out = self.run_check('--head', 'pr')
        self.assertEqual(rc, 2, out)

    def test_unknown_head_is_an_error(self):
        self.commit(self.table())
        rc, out = self.run_check('--head', 'nope')
        self.assertEqual(rc, 2, out)


    # --- heavy rows and recorded failures: named, never folded into a pass count ---
    def skipped_row(self, c, **kw):
        base = dict(variant='live', actual='skipped', distinct='', verdict='SKIPPED-HEAVY', consts='')
        base.update(kw)
        return row(c, **base)

    def test_skipped_heavy_row_is_green_named_and_tallied_apart_from_passes(self):
        c0 = self.commit()
        self.commit(self.table(row(c0), self.skipped_row(c0, module='RabiaSlot')))
        rc, out = self.run_check()
        self.assertEqual(rc, 0, out)
        self.assertIn('tools/tla/results.tsv:3: NOTE SKIPPED-HEAVY RabiaSlot/live', out)
        self.assertIn('not a pass', out)
        self.assertIn('OK 1, RED 0, ERROR 0, RESOURCE 0, SKIPPED-HEAVY 1', out)

    def test_skipped_row_claiming_a_pass_is_red(self):
        c0 = self.commit()
        self.commit(self.table(self.skipped_row(c0, actual='pass')))
        rc, out = self.run_check()
        self.assertEqual(rc, 1, out)
        self.assertIn('SKIPPED-HEAVY needs actual=skipped', out)

    def test_actual_skipped_under_an_ok_verdict_is_red(self):
        c0 = self.commit()
        self.commit(self.table(self.skipped_row(c0, verdict='OK')))
        rc, out = self.run_check()
        self.assertEqual(rc, 1, out)
        self.assertIn('SKIPPED-HEAVY needs actual=skipped', out)

    def test_skipped_row_with_a_state_count_or_outside_small_is_red(self):
        c0 = self.commit()
        self.commit(self.table(self.skipped_row(c0, distinct='5'), self.skipped_row(c0, profile='full', variant='b')))
        rc, out = self.run_check()
        self.assertEqual(rc, 1, out)
        self.assertEqual(out.count('SKIPPED-HEAVY needs actual=skipped'), 2, out)

    def test_ok_row_whose_actual_differs_from_expect_is_red(self):
        c0 = self.commit()
        self.commit(self.table(row(c0, expect='pass', actual='fail', violated='Agreement', verdict='OK')))
        rc, out = self.run_check()
        self.assertEqual(rc, 1, out)
        self.assertIn('verdict OK but actual fail is not the expected pass', out)

    def test_error_verdict_without_actual_error_is_red(self):
        c0 = self.commit()
        self.commit(self.table(row(c0, verdict='ERROR')))
        rc, out = self.run_check()
        self.assertEqual(rc, 1, out)
        self.assertIn('ERROR or RESOURCE and actual=error go together', out)

    def test_resource_verdict_with_actual_error_is_accepted_and_named_never_a_pass(self):
        # check.sh's resource guard killed TLC (exit 75): run.sh records RESOURCE with actual=error
        c0 = self.commit()
        self.commit(self.table(row(c0, expect='pass', actual='error', distinct='', verdict='RESOURCE')))
        rc, out = self.run_check()
        self.assertEqual(rc, 0, out)
        self.assertIn('NOTE RESOURCE', out)
        self.assertIn('OK 0, RED 0, ERROR 0, RESOURCE 1, SKIPPED-HEAVY 0', out)

    def test_resource_verdict_without_actual_error_is_red(self):
        c0 = self.commit()
        self.commit(self.table(row(c0, verdict='RESOURCE')))
        rc, out = self.run_check()
        self.assertEqual(rc, 1, out)
        self.assertIn('ERROR or RESOURCE and actual=error go together', out)

    def test_recorded_red_row_is_green_provenance_and_named(self):
        # RabiaAdopt/ballotlossD5 (#3592): the evidence records the failure; the check verifies where the row came from
        c0 = self.commit()
        self.commit(self.table(row(c0, module='RabiaAdopt', variant='ballotlossD5', expect='pass', actual='fail', violated='Agreement',
                                   distinct='28279', verdict='RED')))
        rc, out = self.run_check()
        self.assertEqual(rc, 0, out)
        self.assertIn('NOTE RED RabiaAdopt/ballotlossD5: expect pass, actual fail, violated Agreement', out)
        self.assertIn('OK 0, RED 1, ERROR 0, RESOURCE 0, SKIPPED-HEAVY 0', out)

    def test_skipped_row_naming_a_foreign_commit_is_still_red(self):
        self.commit()
        self.g('checkout', '-q', '-b', 'other')
        other = self.commit(msg='only on other')
        self.g('checkout', '-q', 'main')
        self.commit(self.table(self.skipped_row(other)))
        rc, out = self.run_check()
        self.assertEqual(rc, 1, out)
        self.assertIn('is not an ancestor of', out)

    # the profile column holds every profile run.sh accepts: small, full, sim, heavy (a heavy run writes profile=heavy)
    def test_a_row_of_the_heavy_profile_is_green_for_pass_fail_and_error(self):
        c0 = self.commit()
        self.commit(self.table(row(c0, profile='heavy', variant='a'),
                               row(c0, profile='heavy', variant='b', expect='fail', actual='fail', violated='Agreement'),
                               row(c0, profile='heavy', variant='c', actual='error', distinct='', verdict='ERROR'),
                               row(c0, profile='full', variant='d'), row(c0, profile='sim', variant='e')))
        rc, out = self.run_check()
        self.assertEqual(rc, 0, out)
        self.assertNotIn('malformed', out)
        self.assertIn('GREEN 0 bad of 5 rows', out)

    def test_a_profile_run_sh_does_not_know_is_still_malformed(self):
        c0 = self.commit()
        self.commit(self.table(row(c0, profile='huge')))
        rc, out = self.run_check()
        self.assertEqual(rc, 1, out)
        self.assertIn('malformed value in column profile', out)

    def test_a_heavy_profile_row_cannot_claim_skipped_heavy(self):
        c0 = self.commit()
        self.commit(self.table(self.skipped_row(c0, profile='heavy')))
        rc, out = self.run_check()
        self.assertEqual(rc, 1, out)
        self.assertIn('SKIPPED-HEAVY needs actual=skipped', out)


if __name__ == '__main__':
    unittest.main()
