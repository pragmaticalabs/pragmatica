"""Tests of writers-check.py on the real DirectiveReceipt model: the script must be green on the model as it stands and
red on each mutation (a check that cannot fail checks nothing).

The probes edit a copy of the extracted module, never the source:
  - one write removed from one action (in the form TLC accepts in silence: the variable moved into the UNCHANGED);
  - one write of a variable that is not expected put into one action;
  - `v' = e /\\ UNCHANGED v`, which TLC takes as a disabled step and reports nothing;
  - a write removed with nothing in its place;
  - a variable the table has no line for, a table line the module has no variable for, and a row taken off a line.
"""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
SCRIPT = os.path.join(HERE, 'writers-check.py')
TABLE = os.path.join(HERE, 'DirectiveReceipt.writers.tsv')
ROOT = os.path.join(REPO, 'aether', 'aether-deployment', 'src', 'main', 'java')

HALT = ("  /\\ role = \"WORKER\" /\\ runs /\\ live /\\ directed /\\ ~directive\n"
        "  /\\ runs' = FALSE /\\ live' = FALSE /\\ ranSince' = FALSE\n"
        "  /\\ UNCHANGED <<role, history, inflight, term, mirror, pending, view, token, directed, retirements, sticky, mark>>")
PARTITION = ("  /\\ live' = FALSE\n"
             "  /\\ UNCHANGED <<role, history, inflight, term, mirror, pending, view, token, runs, directed, retirements, "
             "sticky, ranSince, mark>>")


class WritersCheck(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.mkdtemp(prefix='writers-check-test-')
        done = subprocess.run([sys.executable, os.path.join(HERE, 'extract.py'), '--module', 'DirectiveReceipt',
                               '--out', cls.dir, ROOT], capture_output=True, text=True)
        assert done.returncode == 0, done.stdout + done.stderr
        with open(os.path.join(cls.dir, 'DirectiveReceipt.tla')) as f:
            cls.module = f.read()
        with open(TABLE) as f:
            cls.table = f.read()

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.dir, ignore_errors=True)

    def run_check(self, module=None, table=None):
        tla = os.path.join(self.dir, 'probe.tla')
        tsv = os.path.join(self.dir, 'probe.tsv')
        with open(tla, 'w') as f:
            f.write(self.module if module is None else module)
        with open(tsv, 'w') as f:
            f.write(self.table if table is None else table)
        done = subprocess.run([sys.executable, SCRIPT, '--table', tsv, '--tla', tla], capture_output=True, text=True)
        return done.returncode, done.stdout + done.stderr

    def edited(self, old, new):
        self.assertEqual(self.module.count(old), 1, 'the probe must hit exactly one place: ' + old[:60])
        return self.module.replace(old, new)

    def test_realModel_asItStands_green(self):
        code, out = self.run_check()
        self.assertEqual(code, 0, out)
        self.assertIn('18 variable lines, 15 module variables, 29 rows, 21 actions (23 operators), 0 mismatches', out)

    def test_realModel_extractedByTheScript_green(self):
        done = subprocess.run([sys.executable, SCRIPT, '--table', TABLE, '--module', 'DirectiveReceipt', ROOT],
                              capture_output=True, text=True)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)

    def test_writeRemoved_movedIntoUnchanged_red(self):
        probe = self.edited(HALT, HALT.replace(" /\\ ranSince' = FALSE", '').replace('sticky, mark>>', 'sticky, mark, ranSince>>'))
        code, out = self.run_check(module=probe)
        self.assertEqual(code, 1, out)
        self.assertIn("`Halt` (halt) does not write `ranSince`", out)

    def test_writeRemoved_nothingInItsPlace_red(self):
        probe = self.edited(HALT, HALT.replace(" /\\ ranSince' = FALSE", ''))
        code, out = self.run_check(module=probe)
        self.assertEqual(code, 1, out)
        self.assertIn("`Halt` (halt) does not write `ranSince`", out)
        self.assertIn("`Halt` (halt) neither writes `ranSince` nor leaves it in an UNCHANGED", out)

    def test_writeAdded_notExpected_red(self):
        probe = self.edited(PARTITION, PARTITION.replace("live' = FALSE", "live' = FALSE /\\ directed' = FALSE")
                            .replace('runs, directed, retirements', 'runs, retirements'))
        code, out = self.run_check(module=probe)
        self.assertEqual(code, 1, out)
        self.assertIn("`Partition` (partition) writes `directed`", out)

    def test_writeBesideItsOwnUnchanged_theFormTlcAccepts_red(self):
        probe = self.edited(PARTITION, PARTITION.replace("live' = FALSE", "live' = FALSE /\\ directed' = FALSE"))
        code, out = self.run_check(module=probe)
        self.assertEqual(code, 1, out)
        self.assertIn("`Partition` (partition) both writes `directed` and leaves it UNCHANGED", out)

    def test_moduleVariable_withNoLine_red(self):
        probe = self.edited('          mark         \\*', '          mark, extra  \\*')
        code, out = self.run_check(module=probe)
        self.assertEqual(code, 1, out)
        self.assertIn('the module VARIABLE `extra` has no line in the table', out)

    def test_tableLine_withNoVariable_red(self):
        code, out = self.run_check(table=self.table + 'var\tghost\tvar\t-\n')
        self.assertEqual(code, 1, out)
        self.assertIn('the table line `ghost` is no VARIABLE of the module', out)

    def test_rowTakenOffALine_red(self):
        self.assertEqual(self.table.count('var\ttoken\tvar\tS5\n'), 1)
        code, out = self.run_check(table=self.table.replace('var\ttoken\tvar\tS5\n', 'var\ttoken\tvar\t-\n'))
        self.assertEqual(code, 1, out)
        self.assertIn('`token` lists the rows [] and the heads that name it are [\'S5\']', out)

    def test_stepOfNext_notInTheMap_red(self):
        self.assertEqual(self.table.count('action\tlose\tLose\tS6\n'), 1)
        code, out = self.run_check(table=self.table.replace('action\tlose\tLose\tS6\n', ''))
        self.assertEqual(code, 1, out)
        self.assertIn('`Next` takes the step `Lose`, which no action of the map names', out)


    # Edge run E8 of the DirectiveReceipt model: every bound is a conjunct of an enabling, none is a cfg CONSTRAINT (the
    # word finds ACTION_CONSTRAINT too). Under a constraint TLC's ENABLED ignores the bound, and a fair step that is cut
    # drops the behaviour from the liveness check.
    def constraint_hits(self, directory):
        hits, files = [], 0
        for name in sorted(os.listdir(directory)):
            if name.endswith('.cfg'):
                files += 1
                with open(os.path.join(directory, name)) as f:
                    hits += [name for line in f if 'CONSTRAINT' in line]
        return hits, files

    def test_edgeRunE8_noCfgOfTheModel_holdsAConstraint(self):
        hits, files = self.constraint_hits(self.dir)
        self.assertGreaterEqual(files, 29)
        self.assertEqual(hits, [])

    def test_edgeRunE8_control_theSearchFindsTheWord(self):
        control = tempfile.mkdtemp(prefix='writers-check-e8-')
        try:
            with open(os.path.join(control, 'DirectiveReceipt_control.cfg'), 'w') as f:
                f.write('SPECIFICATION Spec\nACTION_CONSTRAINT Bounded\n')
            self.assertEqual(self.constraint_hits(control), (['DirectiveReceipt_control.cfg'], 1))
        finally:
            shutil.rmtree(control, ignore_errors=True)


if __name__ == '__main__':
    unittest.main()
