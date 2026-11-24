"""Tests of weekly-verdict.py (#3659): a forced red row is RED with its name, zero rows is never GREEN, run-host's exit 0 is not trusted."""
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import importlib.util
spec = importlib.util.spec_from_file_location('weekly_verdict', os.path.join(HERE, 'weekly-verdict.py'))
wv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wv)

HEADER = 'commit\tdirty\tdate\tprofile\tmodule\tvariant\texpect\tactual\tviolated\tdistinct\ttlc\tverdict\tconstants'


def results(*rows):
    """rows: (module, variant, verdict)"""
    return '\n'.join([HEADER] + ['\t'.join(['c', 'clean', 'd', 'full', m, v, 'pass', 'pass', '', '1', 't', verdict, 'K=1']) for m, v, verdict in rows]) + '\n'


def summary(rows, green, red=0, ns=0, word=None):
    word = word or ('GREEN' if red == 0 and ns == 0 else 'RED')
    return f'run-host: profile=full rows={rows} green={green} red={red} not-started={ns} wall=10s {word}\n'


TWO_OK = results(('Fsm2Leader', 'code_goals', 'OK'), ('RabiaAdopt', 'rjT_resync_savefail', 'OK'))


class WeeklyVerdict(unittest.TestCase):
    def test_every_row_ok_is_green(self):
        self.assertEqual(wv.verdict(summary(2, 2), TWO_OK), ('GREEN', 2, [], ''))

    def test_a_forced_red_row_is_red_and_named(self):
        res = results(('Fsm2Leader', 'code_goals', 'OK'), ('RabiaAdopt', 'rjT_resync_savefail', 'RED'))
        self.assertEqual(wv.verdict(summary(2, 1, red=1), res), ('RED', 2, ['RabiaAdopt/rjT_resync_savefail'], 'row-red'))

    def test_a_red_summary_cannot_be_hidden_by_ok_evidence_rows(self):
        self.assertEqual(wv.verdict(summary(2, 2, red=1, word='RED'), TWO_OK)[0], 'RED')

    def test_not_started_and_evidence_less_rows_are_named_red(self):
        log = 'NOT-STARTED Fsm2Leader/code_goals budget 10800s/6g: MemAvailable 100 MB\nrun-host: RED RabiaAdopt/x: run.sh wrote no evidence row\n' + summary(2, 0, 1, 1)
        s, n, red, why = wv.verdict(log, '')
        self.assertEqual((s, red), ('RED', ['Fsm2Leader/code_goals', 'RabiaAdopt/x']))

    def test_run_host_exit_zero_with_nothing_to_run_is_not_green(self):
        log = "run-host: profile=full: no row over the hosted limit matches '.*' / '.*'; nothing to run\n"
        self.assertEqual(wv.verdict(log, ''), ('NOT-GREEN', 0, [], 'zero-rows'))

    def test_a_green_summary_with_rows_zero_is_not_green(self):
        self.assertEqual(wv.verdict(summary(0, 0), results()), ('NOT-GREEN', 0, [], 'zero-rows'))

    def test_a_green_summary_without_evidence_rows_is_not_green(self):
        self.assertEqual(wv.verdict(summary(2, 2), results()), ('NOT-GREEN', 2, [], 'no-evidence'))

    def test_a_green_summary_with_fewer_evidence_rows_than_listed_is_not_green(self):
        self.assertEqual(wv.verdict(summary(2, 2), results(('A', 'b', 'OK'))), ('NOT-GREEN', 2, [], 'inconsistent'))

    def test_no_log_is_not_green(self):
        self.assertEqual(wv.verdict('', ''), ('NOT-GREEN', 0, [], 'no-summary'))

    def test_cli_prints_one_line_and_exits_zero_for_every_status(self):
        with tempfile.TemporaryDirectory() as d:
            log, res = os.path.join(d, 'l'), os.path.join(d, 'r')
            with open(log, 'w') as f:
                f.write(summary(2, 1, red=1))
            with open(res, 'w') as f:
                f.write(results(('A', 'b', 'RED')))
            p = subprocess.run([sys.executable, os.path.join(HERE, 'weekly-verdict.py'), log, res], capture_output=True, text=True)
            self.assertEqual((p.returncode, p.stdout), (0, 'RED\t1\tA/b\trow-red\n'))
            p = subprocess.run([sys.executable, os.path.join(HERE, 'weekly-verdict.py'), '/nonexistent', '/nonexistent'], capture_output=True, text=True)
            self.assertEqual((p.returncode, p.stdout), (0, 'NOT-GREEN\t0\t\tno-summary\n'))


if __name__ == '__main__':
    unittest.main()
