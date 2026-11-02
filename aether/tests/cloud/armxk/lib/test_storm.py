#!/usr/bin/env python3
"""The idle-storm flag's rule, pinned without a cluster: `python3 aether/tests/cloud/armxk/lib/test_storm.py`."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import armxk  # noqa: E402


def core(slots, commits, pending_end, pending_start=0):
    return {"slots": slots, "decisions": slots, "commits": commits, "pendingStart": pending_start, "pendingEnd": pending_end,
            "oldestPendingAgeMsEnd": 0, "heapUsed": 1, "heapMax": 2}


class StormFlagTest(unittest.TestCase):
    def test_storm_flag_fires_whenManySlotsPerCommitAndAPendingBatch(self):
        # dry7's s0 window: 21217 slots, 1 commit; inv-idle-spin's holder keeps one batch
        health = {"core-1": core(21217, 1, 0), "core-2": core(21217, 1, 1)}
        self.assertTrue(armxk.storm_flag(health))

    def test_storm_flag_quiet_whenNoCoreHoldsAPendingBatch(self):
        self.assertFalse(armxk.storm_flag({"core-1": core(21217, 1, 0), "core-2": core(21217, 1, 0)}))

    def test_storm_flag_quiet_whenSlotsTrackCommits(self):
        # arm K under load: a pending batch is normal and slots stay near commits
        self.assertFalse(armxk.storm_flag({"core-1": core(9000, 8000, 3), "core-2": core(9000, 8000, 2)}))

    def test_storm_flag_boundary_isStrictlyAboveTheRatio(self):
        limit = armxk.STORM_SLOTS_PER_COMMIT
        self.assertFalse(armxk.storm_flag({"core-1": core(limit, 1, 1)}))
        self.assertTrue(armxk.storm_flag({"core-1": core(limit + 1, 1, 1)}))

    def test_storm_flag_zeroCommits_countsAsOne(self):
        self.assertTrue(armxk.storm_flag({"core-1": core(armxk.STORM_SLOTS_PER_COMMIT + 1, 0, 1)}))
        self.assertFalse(armxk.storm_flag({"core-1": core(0, 0, 1)}))
        # a few empty slots in a write-free window (a lone timer slot, a reconfiguration) are not a storm
        self.assertFalse(armxk.storm_flag({"core-1": core(10, 0, 1)}))

    def test_storm_flag_quiet_whenNoReadings(self):
        self.assertFalse(armxk.storm_flag({}))

    def test_window_health_readsEveryField(self):
        before = {"engine.slots": 10, "put.total": 4, "remove.total": 1, "heap.used": 5, "heap.max": 9}
        after = {"engine.slots": 70, "put.total": 6, "remove.total": 2, "heap.used": 7, "heap.max": 9}
        rb = {"decisionsCount": 11, "pendingBatches": 0}
        ra = {"decisionsCount": 71, "pendingBatches": 2, "oldestPendingBatchAgeMs": 4000}
        self.assertEqual(armxk.window_health(before, after, rb, ra),
                         {"slots": 60, "decisions": 60, "commits": 3, "pendingStart": 0, "pendingEnd": 2, "oldestPendingAgeMsEnd": 4000,
                          "heapUsed": 7, "heapMax": 9})

    def test_delta_ignoresHeapGauges(self):
        self.assertEqual(armxk.delta({"heap.used": 5, "put.total": 1}, {"heap.used": 9, "put.total": 2}), {"put.total": 1})


class FakeRun:
    """Just what run_class touches: an evidence dir, emit, and a core check that can be made to fail."""

    def __init__(self, directory, dead_after=None):
        self.dir = Path(directory)
        (self.dir / "evidence").mkdir()
        self.events, self.checks, self.dead_after = [], 0, dead_after

    def emit(self, name, record):
        self.events.append((name, record))

    def check_cores(self):
        self.checks += 1
        if self.dead_after is not None and self.checks > self.dead_after:
            raise armxk.HarnessError("core died; node core-2 process is gone after an OutOfMemoryError; aborting the run")


def storming_class(storms_per_call):
    calls = []

    def fn(run):
        calls.append(1)
        with open(run.dir / "evidence" / "storm.jsonl", "a") as f:
            for _ in range(storms_per_call[len(calls) - 1]):
                f.write(json.dumps({"storm": True}) + "\n")
    return fn, calls


class RunClassTest(unittest.TestCase):
    def setUp(self):
        self.pulled = []
        self.saved = (armxk.spend_check, armxk.pull_evidence)
        armxk.spend_check = lambda run, minutes: None
        armxk.pull_evidence = lambda run, label: self.pulled.append(label)

    def tearDown(self):
        armxk.spend_check, armxk.pull_evidence = self.saved

    def test_run_class_once_whenNoStorm(self):
        with tempfile.TemporaryDirectory() as d:
            run = FakeRun(d)
            fn, calls = storming_class([0, 0])
            self.assertEqual(armxk.run_class(run, "s0", fn, 1), 1)
            self.assertEqual((len(calls), self.pulled), (1, ["s0"]))

    def test_run_class_rerunsOnce_whenAWindowStorms(self):
        with tempfile.TemporaryDirectory() as d:
            run = FakeRun(d)
            fn, calls = storming_class([2, 0])
            self.assertEqual(armxk.run_class(run, "s3", fn, 1), 2)
            self.assertEqual((len(calls), self.pulled), (2, ["s3", "s3-rerun"]))
            self.assertEqual([e[1]["kind"] for e in run.events], ["storm-rerun"])

    def test_run_class_neverAThirdTime_whenTheRerunStormsToo(self):
        with tempfile.TemporaryDirectory() as d:
            run = FakeRun(d)
            fn, calls = storming_class([1, 1, 1])
            self.assertEqual(armxk.run_class(run, "s4", fn, 1), 2)
            self.assertEqual(len(calls), 2)
            self.assertEqual([e[1]["kind"] for e in run.events], ["storm-rerun", "storm-after-rerun"])

    def test_run_class_aborts_whenACoreDiesDuringTheClass(self):
        with tempfile.TemporaryDirectory() as d:
            run = FakeRun(d, dead_after=1)
            fn, calls = storming_class([0, 0])
            with self.assertRaises(armxk.HarnessError) as raised:
                armxk.run_class(run, "s6", fn, 1)
            self.assertIn("OutOfMemoryError", str(raised.exception))
            self.assertEqual((len(calls), self.pulled), (1, []))


class DeathNoteTest(unittest.TestCase):
    def note(self, answer):
        run = armxk.Run.__new__(armxk.Run)
        run.root = "/r"
        run.sh = lambda host, command, **kw: answer
        return run.death_note({"id": "core-2", "host": "h"})

    def test_death_note_empty_whenAlive(self):
        self.assertEqual(self.note("alive\n"), "")

    def test_death_note_namesTheOom(self):
        self.assertEqual(self.note("dead 1\n"), "; node core-2 process is gone after an OutOfMemoryError")

    def test_death_note_plainDeath_withoutOom(self):
        self.assertEqual(self.note("dead 0\n"), "; node core-2 process is gone")


if __name__ == "__main__":
    unittest.main()
