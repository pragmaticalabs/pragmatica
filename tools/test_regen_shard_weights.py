import importlib.util
import tempfile
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location("regen", Path(__file__).with_name("regen-shard-weights.py"))
regen = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(regen)

# Real surefire reports from CI run 41351583462 (rc5 69b24d0f3a), with <properties> and <system-out> removed.
RUN = "41351583462"
FIXTURE = Path(__file__).resolve().parent / f"testdata/shard-weights-run-{RUN}"


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

    def test_main_refuses_to_rewrite_from_zero_reports(self):
        with tempfile.TemporaryDirectory() as d, self.assertRaises(SystemExit) as raised:
            regen.main(["--module", "ember", "--run", RUN, "--dir", d])
        self.assertIn("refusing to rewrite", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
