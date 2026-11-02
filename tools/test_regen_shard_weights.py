import contextlib
import importlib.util
import io
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

_spec = importlib.util.spec_from_file_location("regen", Path(__file__).with_name("regen-shard-weights.py"))
regen = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(regen)

# Real surefire reports from CI run 41351583462 (rc5 69b24d0f3a), with <properties> and <system-out> removed.
RUN = "41351583462"
FIXTURE = Path(__file__).resolve().parent / f"testdata/shard-weights-run-{RUN}"
CI = Path(__file__).resolve().parent.parent / ".github/workflows/ci.yml"


class RegenShardWeightsTest(unittest.TestCase):
    def test_fixture_is_read(self):
        # guards the guard: a wrong fixture path would make the reproduction below compare two empty sets
        self.assertEqual(len(list(FIXTURE.rglob("TEST-*.xml"))), 7)

    def test_suite_times_are_summed_per_top_level_class_across_nested_reports(self):
        measured = regen.measured_seconds([FIXTURE / "ember-test-results-1"])
        self.assertEqual(sorted(measured), ["EmberAllHoldersRestartKeepRetirementTest", "EmberConfigBasePortTest"])
        self.assertAlmostEqual(measured["EmberAllHoldersRestartKeepRetirementTest"], 23.99)
        # TEST-...EmberConfigBasePortTest.xml 0.01 + $Defaults 0.001 + $Parsing 0.001 + $Validation 0.003
        self.assertAlmostEqual(measured["EmberConfigBasePortTest"], 0.015)
        both = regen.measured_seconds([FIXTURE / "consensus-test-results-1", FIXTURE / "consensus-test-results-2"])
        # one report holding @Nested testcases, and one artifact per shard
        self.assertEqual(both, {"QuicConsensusBackpressureTest": 0.37, "QuicBootTokenAdmissionTest": 69.661})

    def test_regenerate_from_real_reports_reproduces_the_expected_weights(self):
        measured = regen.measured_seconds([FIXTURE])
        with tempfile.TemporaryDirectory() as d:
            old = Path(d) / "w.txt"
            old.write_text("# hand header\nEmberAllHoldersRestartKeepRetirementTest 99\nQuicDialAttemptTest 41  # bigboy, 3 runs\n"
                           "QuicGoneTest 7\nQuicBareTest 3\n")
            classes = ["EmberAllHoldersRestartKeepRetirementTest", "EmberConfigBasePortTest", "QuicBootTokenAdmissionTest",
                       "QuicConsensusBackpressureTest", "QuicDialAttemptTest", "QuicBareTest", "QuicUnweightedTest"]
            text, carried = regen.regenerate("ember", RUN, classes, measured, regen.previous_lines(old))
            (Path(d) / "new.txt").write_text(text)
            self.assertEqual(regen.shard.read_weights(Path(d) / "new.txt"), {
                "EmberAllHoldersRestartKeepRetirementTest": 24,  # 23.99 s, re-measured over the old 99
                "EmberConfigBasePortTest": 1,                    # 0.015 s rounded up
                "QuicBootTokenAdmissionTest": 70,                # 69.661 s
                "QuicConsensusBackpressureTest": 1,              # 0.37 s
                "QuicDialAttemptTest": 41,                       # not in the run: carried forward
                "QuicBareTest": 3,                               # carried forward; QuicGoneTest left the source tree
            })
        self.assertEqual(carried, ["QuicBareTest", "QuicDialAttemptTest"])
        self.assertIn(f"EmberAllHoldersRestartKeepRetirementTest 24  # measured at run {RUN}\n", text)
        self.assertIn("QuicDialAttemptTest 41  # bigboy, 3 runs\n", text)
        self.assertIn(f"QuicBareTest 3  # carried forward, not reported by run {RUN}\n", text)
        self.assertIn(f"--run {RUN}", text)

    def test_float_noise_in_a_sum_does_not_round_up_a_whole_second(self):
        # three millisecond suite times summing to 28.000 s add up, in report-file order ($A, $B, outer), to 28.000000000000004 s
        with tempfile.TemporaryDirectory() as d:
            for name, time in (("XTest$A", "3.495"), ("XTest$B", "17.638"), ("XTest", "6.867")):
                (Path(d) / f"TEST-org.x.{name}.xml").write_text(f'<testsuite name="org.x.{name}" time="{time}"/>')
            measured = regen.measured_seconds([d])
        self.assertGreater(measured["XTest"], 28)  # guards the guard: without the noise this test proves nothing
        text, _ = regen.regenerate("ember", RUN, ["XTest"], measured, {})
        self.assertIn("XTest 28  #", text)

    def test_a_grouped_suite_time_is_parsed(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "TEST-org.x.XTest.xml").write_text('<testsuite name="org.x.XTest" time="1,234.5"/>')
            self.assertEqual(regen.measured_seconds([d]), {"XTest": 1234.5})

    def test_a_report_without_a_time_exits_naming_it(self):
        with tempfile.TemporaryDirectory() as d:
            report = Path(d) / "TEST-org.x.XTest.xml"
            report.write_text('<testsuite name="org.x.XTest" tests="1"/>')
            with self.assertRaises(SystemExit) as raised:
                regen.measured_seconds([d])
        self.assertEqual(str(raised.exception), f"{report}: <testsuite> has no time attribute; refusing to weigh its class at 0 s")

    def _module(self):
        """A synthetic module whose source tree plans the fixture's four classes plus one the run never reported."""
        d = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, d)
        (d / "src").mkdir()
        for c in ("EmberAllHoldersRestartKeepRetirementTest", "EmberConfigBasePortTest", "QuicBootTokenAdmissionTest",
                  "QuicConsensusBackpressureTest", "QuicDialAttemptTest"):
            (d / "src" / f"{c}.java").write_text(f"class {c} {{ @Test void t() {{}} }}")
        weights = d / "w.txt"
        weights.write_text("QuicDialAttemptTest 41\n")
        return d / "src", weights

    def _main(self, src, weights, *argv):
        err = io.StringIO()
        with mock.patch.dict(regen.shard.MODULES, {"synthetic": (src, weights, 0)}), contextlib.redirect_stderr(err):
            regen.main(["--module", "synthetic", "--run", RUN, *argv])
        return err.getvalue()

    def test_main_rewrites_the_module_weights_file(self):
        src, weights = self._module()
        err = self._main(src, weights, "--dir", str(FIXTURE))
        self.assertEqual(regen.shard.read_weights(weights), {
            "EmberAllHoldersRestartKeepRetirementTest": 24, "EmberConfigBasePortTest": 1, "QuicBootTokenAdmissionTest": 70,
            "QuicConsensusBackpressureTest": 1, "QuicDialAttemptTest": 41})
        self.assertIn(f"4 class(es) measured at run {RUN}, 1 carried forward: QuicDialAttemptTest", err)

    def test_main_without_a_dir_downloads_the_runs_module_artifacts(self):
        src, weights = self._module()
        calls = []
        def fake_run(args, check):
            calls.append((args, check))
            shutil.copytree(FIXTURE, args[-1], dirs_exist_ok=True)
        with mock.patch.object(regen.subprocess, "run", fake_run):
            self._main(src, weights)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][0][:6], ["gh", "run", "download", RUN, "-p", "synthetic-test-results-*"])
        self.assertEqual((calls[0][0][6], calls[0][1]), ("-D", True))
        self.assertEqual(regen.shard.read_weights(weights)["QuicBootTokenAdmissionTest"], 70)

    def test_ci_runs_these_pins(self):
        self.assertIn("python3 -B -m unittest discover -s tools -p test_regen_shard_weights.py", CI.read_text())

    def test_main_refuses_to_rewrite_from_zero_reports(self):
        src, weights = self._module()  # a synthetic module, so a broken refusal rewrites no tracked file
        with tempfile.TemporaryDirectory() as d, self.assertRaises(SystemExit) as raised:
            self._main(src, weights, "--dir", d)
        self.assertIn("refusing to rewrite", str(raised.exception))
        self.assertEqual(weights.read_text(), "QuicDialAttemptTest 41\n")


if __name__ == "__main__":
    unittest.main()
