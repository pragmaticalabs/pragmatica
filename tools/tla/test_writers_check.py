"""Tests of writers-check.py on the real DirectiveReceipt model: green on the model as it stands, red on each mutation (a
check that cannot fail checks nothing).

Every probe edits a copy of the extracted module, of its table or of its cfg files, never the source. The probes of the
writes are the entrances of one hole: a step that TLC takes as disabled, or as narrower, and reports nothing.
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
GREEN = '18 variable lines, 15 module variables, 29 rows, 21 actions (24 operators, 24 paths)'

HALT = ("  /\\ role = \"WORKER\" /\\ runs /\\ live /\\ directed /\\ ~directive\n"
        "  /\\ runs' = FALSE /\\ live' = FALSE /\\ ranSince' = FALSE\n"
        "  /\\ UNCHANGED <<role, history, inflight, term, mirror, pending, view, token, directed, retirements, sticky, mark>>")
PARTITION = ("  /\\ live' = FALSE\n"
             "  /\\ UNCHANGED <<role, history, inflight, term, mirror, pending, view, token, runs, directed, retirements, "
             "sticky, ranSince, mark>>")
NEXT_END = "  \\/ LeaderChange \\/ Restart \\/ Partition \\/ Reconnect \\/ Stop\n"
SPEC = "Spec == Init /\\ [][Next]_vars /\\ Fairness\n"


class WritersCheck(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.mkdtemp(prefix='writers-check-test-')
        cls.cfgs = os.path.join(cls.dir, 'model')
        done = subprocess.run([sys.executable, os.path.join(HERE, 'extract.py'), '--module', 'DirectiveReceipt',
                               '--out', cls.cfgs, ROOT], capture_output=True, text=True)
        assert done.returncode == 0, done.stdout + done.stderr
        with open(os.path.join(cls.cfgs, 'DirectiveReceipt.tla')) as f:
            cls.module = f.read()
        with open(TABLE) as f:
            cls.table = f.read()

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.dir, ignore_errors=True)

    def run_check(self, module=None, table=None, cfgdir=None):
        tla = os.path.join(self.dir, 'probe.tla')
        tsv = os.path.join(self.dir, 'probe.tsv')
        with open(tla, 'w') as f:
            f.write(self.module if module is None else module)
        with open(tsv, 'w') as f:
            f.write(self.table if table is None else table)
        done = subprocess.run([sys.executable, SCRIPT, '--table', tsv, '--tla', tla, '--cfgdir', cfgdir or self.cfgs],
                              capture_output=True, text=True)
        return done.returncode, done.stdout + done.stderr

    def edited(self, old, new):
        self.assertEqual(self.module.count(old), 1, 'the probe must hit exactly one place: ' + old[:60])
        return self.module.replace(old, new)

    def red(self, message, module=None, table=None, cfgdir=None):
        code, out = self.run_check(module, table, cfgdir)
        self.assertEqual(code, 1, out)
        self.assertIn(message, out)

    # -- green ------------------------------------------------------------------------------------------------------
    def test_realModel_asItStands_green(self):
        code, out = self.run_check()
        self.assertEqual(code, 0, out)
        self.assertIn(GREEN, out)
        self.assertIn('0 mismatches', out)

    def test_realModel_extractedByTheScript_greenWithItsCfgFiles(self):
        done = subprocess.run([sys.executable, SCRIPT, '--table', TABLE, '--module', 'DirectiveReceipt', ROOT],
                              capture_output=True, text=True)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertRegex(done.stdout, r'\), [1-9][0-9]* cfg files, 0 mismatches')

    def test_bounds_areReportedWithTheStepsThatHoldThem(self):
        _, out = self.run_check()
        self.assertIn('bound E_max (line): in the enabling of Admit, ArmJ1; fair: ArmJ1', out)
        self.assertIn('bound H_max (line): in the enabling of Commit; none of them fair', out)
        self.assertIn('bound MaxRet (input): in the enabling of ExternalConfirm, ForcedClear, Terminate; none of them fair', out)
        self.assertIn('bound MaxTerm (input): in the enabling of LeaderChange; none of them fair', out)
        self.assertIn('bound MaxToken (input): in the enabling of Restart; none of them fair', out)

    # -- the writes: every variable exactly once per path, in one of two literal forms ---------------------------------
    def test_writeBesideItsOwnUnchanged_theFormTlcAccepts_red(self):
        self.red("`Partition` (partition), path 1 constrains `live` 2 times (1 written, 1 UNCHANGED)",
                 self.edited(PARTITION, PARTITION.replace('token, runs, directed,', 'token, runs, live, directed,')))

    def test_missingWrite_movedIntoUnchanged_red(self):
        probe = HALT.replace(" /\\ ranSince' = FALSE", '').replace('sticky, mark>>', 'sticky, mark, ranSince>>')
        self.red("`Halt` (halt), path 1 does not write `ranSince`", self.edited(HALT, probe))

    def test_missingWrite_nothingInItsPlace_red(self):
        self.red("`Halt` (halt), path 1 neither writes `ranSince` nor leaves it in an UNCHANGED",
                 self.edited(HALT, HALT.replace(" /\\ ranSince' = FALSE", '')))

    def test_missingWrite_spelledAsVPrimeEqualsV_red(self):
        self.red("holds `live' = live`: no write; say UNCHANGED",
                 self.edited(PARTITION, PARTITION.replace("live' = FALSE", "live' = live")))

    def test_extraWrite_byName_red(self):
        probe = PARTITION.replace("live' = FALSE", "live' = FALSE /\\ directed' = FALSE").replace('runs, directed, ', 'runs, ')
        self.red("`Partition` (partition), path 1 writes `directed`, which is not expected of it", self.edited(PARTITION, probe))

    def test_extraWrite_besideItsOwnUnchanged_red(self):
        self.red("`Partition` (partition), path 1 constrains `directed` 2 times",
                 self.edited(PARTITION, PARTITION.replace("live' = FALSE", "live' = FALSE /\\ directed' = FALSE")))

    def test_extraWrite_throughAHelperOperator_red(self):
        probe = PARTITION.replace("live' = FALSE", "live' = FALSE\n  /\\ Raise(1)").replace('mirror, pending, view, token', 'mirror, token')
        self.red("`Partition` (partition), path 1 writes `pending`, which is not expected of it", self.edited(PARTITION, probe))

    def test_secondWrite_throughAHelperOperator_red(self):
        old = "  /\\ On(\"joinInput\")\n  /\\ Raise(L)\n"
        self.red("`JoinInput` (joinInput), path 1 constrains `pending` 2 times (2 written, 0 UNCHANGED)",
                 self.edited(old, old + "  /\\ Raise(L)\n"))

    def test_twoWritesOfOneVariable_red(self):
        self.red("`Partition` (partition), path 1 constrains `live` 2 times (2 written, 0 UNCHANGED)",
                 self.edited(PARTITION, PARTITION.replace("live' = FALSE", "live' = FALSE /\\ live' = runs")))

    def test_writeThroughAParameter_cannotBeAttributed_red(self):
        probe = self.edited(PARTITION, PARTITION.replace("live' = FALSE", 'Put(live, FALSE)'))
        probe = probe.replace('\nPartition ==', "\nPut(var, val) == var' = val\n\nPartition ==")
        self.red("primes `var`, which is no variable written by name", probe)

    def test_primedTuple_cannotBeAttributed_red(self):
        self.red("which is no variable written by name",
                 self.edited(PARTITION, PARTITION.replace("live' = FALSE", "<<live>>' = <<FALSE>>")))

    def test_primeOfADefinition_theAliasWrite_red(self):
        self.red("primes `record`, which is no variable written by name",
                 self.edited(PARTITION, PARTITION.replace("live' = FALSE", "live' = FALSE /\\ record' = NoRec")))

    def test_primeInAComparison_red(self):
        self.red("primes `token` outside the forms `token' = e` and `token' \\in S`",
                 self.edited(PARTITION, PARTITION.replace("live' = FALSE", "live' = FALSE /\\ token' <= 2")))

    def test_writeInsideAnIf_notAtTheStartOfAConjunct_red(self):
        probe = PARTITION.replace("  /\\ live' = FALSE", "  /\\ IF runs THEN live' = FALSE ELSE live' = TRUE")
        self.red("writes `live` in the middle of a formula", self.edited(PARTITION, probe))

    # -- the steps: Next, Spec, Fairness, the bounds ----------------------------------------------------------------------
    def test_boundAsAConjunctOfNext_red(self):
        self.red("`Next` is no plain disjunction of steps: it holds a conjunct or a prime of its own",
                 self.edited(NEXT_END, NEXT_END + "  /\\ Len(history') <= H_max\n"))

    def test_boundTakenOutOfItsStep_red(self):
        self.red("`MaxTerm` is in the enabling of no step", self.edited("  /\\ term.n < MaxTerm\n", ''))

    def test_inputBoundInAFairStep_red(self):
        old = "  /\\ M(L).rec.e < E_max\n"
        self.red("the input bound `MaxToken` is in the enabling of the fair step(s) ['ArmJ1']",
                 self.edited(old, old + "  /\\ token < MaxToken\n"))

    def test_specWithMoreThanTheCellsFairness_red(self):
        self.red("`Spec` is not exactly `Init /\\ [][Next]_vars /\\ Fairness`",
                 self.edited(SPEC, SPEC.rstrip('\n') + " /\\ WF_vars(Next)\n"))

    def test_fairnessOnAStepNoCellMarksWeak_red(self):
        old = "  /\\ \\A r \\in RetIds : WF_vars(Attempt(r))\n"
        self.red("`Depart` is under WF in `Fairness` or on a `fair` line, and not both",
                 self.edited(old, old + "  /\\ \\A L \\in Leaders : WF_vars(Depart(L))\n"))

    def test_stepOfNext_notInTheMap_red(self):
        self.assertEqual(self.table.count('action\tlose\tLose\tS6\n'), 1)
        self.red('`Next` takes the step `Lose`, which no action of the map names',
                 table=self.table.replace('action\tlose\tLose\tS6\n', ''))

    # -- the table ------------------------------------------------------------------------------------------------------------
    def test_moduleVariable_withNoLine_red(self):
        self.red('the module VARIABLE `extra` has no line in the table',
                 self.edited('          mark         \\*', '          mark, extra  \\*'))

    def test_tableLine_withNoVariable_red(self):
        self.red('the table line `ghost` is no VARIABLE of the module', table=self.table + 'var\tghost\tvar\t-\n')

    def test_rowTakenOffALine_red(self):
        self.assertEqual(self.table.count('var\ttoken\tvar\tS5\n'), 1)
        self.red("`token` lists the rows [] and the heads that name it are ['S5']",
                 table=self.table.replace('var\ttoken\tvar\tS5\n', 'var\ttoken\tvar\t-\n'))

    def test_constantThatIsNeitherBoundNorParam_red(self):
        self.assertEqual(self.table.count('bound\tMaxRet\tinput\t-\n'), 1)
        self.red('the CONSTANT `MaxRet` is neither a `bound` nor a `param` of the table',
                 table=self.table.replace('bound\tMaxRet\tinput\t-\n', ''))

    # -- the cfg files (edge run E8 of the model, folded into the script) ---------------------------------------------------
    def cfg_copy(self, name, edit):
        copy = os.path.join(self.dir, 'cfgs-' + name)
        shutil.copytree(self.cfgs, copy)
        path = os.path.join(copy, 'DirectiveReceipt_receipt_depart.cfg')
        with open(path) as f:
            text = f.read()
        with open(path, 'w') as f:
            f.write(edit(text))
        return copy

    def test_cfgWithAConstraint_red(self):
        self.red('DirectiveReceipt_receipt_depart.cfg holds a CONSTRAINT',
                 cfgdir=self.cfg_copy('constraint', lambda text: text + 'ACTION_CONSTRAINT Bounded\n'))

    def test_cfgWithAnotherSpecification_red(self):
        self.red("DirectiveReceipt_receipt_depart.cfg names the specification ['SpecStep'], expected ['Spec']",
                 cfgdir=self.cfg_copy('spec', lambda text: text.replace('SPECIFICATION Spec', 'SPECIFICATION SpecStep')))

    def test_extraSpecification_takingAStepOutsideTheMap_red(self):
        old = "SpecStep == InitStep /\\ [][\\E t \\in inflight : Commit(t)]_vars\n"
        self.red('the specification `SpecStep` takes the step `Shortcut`, which no action of the map names',
                 self.edited(old, old.replace('Commit(t)]', 'Commit(t) \\/ Shortcut]')))


if __name__ == '__main__':
    unittest.main()
