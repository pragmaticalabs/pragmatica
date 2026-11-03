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

    def test_storm_flag_fires_withNoPendingBatch(self):
        # p4s6's N=100 Noop-driven flood: pendingBatches 0 on every core; the ratio alone must flag it
        self.assertTrue(armxk.storm_flag({"core-1": core(21217, 1, 0), "core-2": core(21217, 1, 0)}))

    def test_storm_flag_fires_whenAnyOneCoreStorms(self):
        # a window whose reference cores disagree (one lagging core) still flags on the storming one
        self.assertTrue(armxk.storm_flag({"core-1": core(21217, 1, 0), "core-2": core(1, 1, 0)}))

    def test_storm_check_dumpsEveryCore_evenWithNoPendingBatch(self):
        dumped, emitted = [], []

        class Dumping:
            def nodes(self, role):
                return [{"id": "core-1"}, {"id": "core-2"}]

            def node(self, cid):
                return {"id": cid}

            def agent(self, n, path):
                dumped.append((n["id"], path))
                return "count 0\n"

            def emit(self, name, record):
                emitted.append(name)

        out = armxk.storm_check(Dumping(), "w", {"core-1": core(21217, 1, 0), "core-2": core(21217, 1, 0)})
        self.assertEqual((out["storm"], sorted(dumped), emitted),
                         (True, [("core-1", "/m1/pending"), ("core-2", "/m1/pending")], ["storm"]))
        self.assertEqual(out["holders"], {"core-1": ["count 0"], "core-2": ["count 0"]})

    def test_storm_flag_quiet_whenOnlyAPendingBatch(self):
        # a pending batch with slots tracking commits is ordinary load, not a storm
        self.assertFalse(armxk.storm_flag({"core-1": core(20, 20, 5)}))

    def test_storm_flag_quiet_whenSlotsTrackCommits(self):
        # arm K under load: slots stay near commits
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
        rb = {"decisionsCount": 11, "pendingBatches": 0, "oldestPendingBatchAgeMs": 0}
        ra = {"decisionsCount": 91, "pendingBatches": 2, "oldestPendingBatchAgeMs": 4000}
        self.assertEqual(armxk.window_health(before, after, rb, ra),
                         {"slots": 60, "decisions": 80, "commits": 3, "pendingStart": 0, "pendingEnd": 2, "oldestPendingAgeMsEnd": 4000,
                          "heapUsed": 7, "heapMax": 9})

    def test_storm_threshold_isFiftySlotsPerCommit(self):
        # literal numbers, so a changed constant goes red (the tests above read the constant and would follow it)
        self.assertEqual(armxk.STORM_SLOTS_PER_COMMIT, 50)
        self.assertTrue(armxk.storm_flag({"core-1": core(51, 1, 0)}))
        self.assertFalse(armxk.storm_flag({"core-1": core(50, 1, 0)}))
        self.assertTrue(armxk.storm_flag({"core-1": core(5001, 100, 0)}))
        self.assertFalse(armxk.storm_flag({"core-1": core(5000, 100, 0)}))

    def test_window_health_missingRabiaReading_isAnError_neverAZero(self):
        snap = {"engine.slots": 1, "heap.used": 1, "heap.max": 2}
        full = {"decisionsCount": 1, "pendingBatches": 0, "oldestPendingBatchAgeMs": 0}
        for key in full:
            partial = {k: v for k, v in full.items() if k != key}
            with self.assertRaises(armxk.HarnessError, msg=key):
                armxk.window_health(snap, snap, full, partial)
        for key in ("heap.used", "heap.max"):
            with self.assertRaises(armxk.HarnessError, msg=key):
                armxk.window_health(snap, {k: v for k, v in snap.items() if k != key}, full, full)

    def test_window_health_absentAgentCounter_isATrueZero(self):
        # the agent creates a counter on first use: no remove.total before or after means no remove committed
        rabia = {"decisionsCount": 1, "pendingBatches": 0, "oldestPendingBatchAgeMs": 0}
        health = armxk.window_health({"heap.used": 1, "heap.max": 2}, {"engine.slots": 3, "put.total": 1, "heap.used": 1, "heap.max": 2},
                                     rabia, rabia)
        self.assertEqual((health["slots"], health["commits"]), (3, 1))

    def test_storm_check_dumpsCoresOutsideTheWindow(self):
        # an idle bracket measures one reference core; the dump still covers every live core
        dumped = []

        class Dumping:
            def nodes(self, role):
                return [{"id": "core-1"}, {"id": "core-2"}, {"id": "core-3"}]

            def node(self, cid):
                return {"id": cid}

            def agent(self, n, path):
                dumped.append(n["id"])
                return "count 1\nhead.id x\n"

            def emit(self, name, record):
                pass

        armxk.storm_check(Dumping(), "idle", {"core-1": core(21217, 1, 0)})
        self.assertEqual(sorted(dumped), ["core-1", "core-2", "core-3"])

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


class ArmKSummaryTest(unittest.TestCase):
    """The arm K summary after a STORM: a rerun appends a second attempt to armk.jsonl, and a c=0 control can itself storm."""

    def summarize(self, rows, attempt):
        class R:
            pass

        with tempfile.TemporaryDirectory() as d:
            run = R()
            run.dir, run.attempt, emitted = Path(d), attempt, []
            (run.dir / "evidence").mkdir()
            (run.dir / "evidence" / "armk.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows))
            run.emit = lambda name, record: emitted.append(record)
            armxk.summarize_armk(run)
            return emitted[-1]

    @staticmethod
    def step(c, ops, attempt, storm=False, probe=None):
        row = {"kind": "armk-step", "pass": 1, "c": c, "target": "core-1", "storm": storm, "attempt": attempt,
               "driver": {"ok": 1, "opsPerSec": ops, "p50Ms": 1, "p99Ms": 5, "errors": 0},
               "cores": {"core-1": {"seconds": 10, "d_decisionsCount": ops * 10, "d_putConfigKey": ops * 10}},
               "exact": {"commitsPerOp": 1.0, "withinBound": True}}
        if probe:
            row["pendingProbe"] = probe
        return row

    @staticmethod
    def control(decisions, attempt, storm=False):
        return {"kind": "armk-control", "pass": 1, "c": 0, "storm": storm, "attempt": attempt,
                "cores": {"core-1": {"seconds": 10, "d_decisionsCount": decisions}}}

    def test_summary_readsOnlyThisAttempt(self):
        rows = [self.step(c, ops, a) for a in (1, 2) for c, ops in ((1, 100), (4, 400), (16, 1600))]
        rows += [self.control(0, 1), self.control(0, 2)]
        table = self.summarize(rows, 2)["passes"]["1"]
        self.assertEqual(([t["c"] for t in table["steps"]], table["plateauC"]), ([1, 4, 16], None))

    def test_summary_stormControl_neverEntersTheIdleBaseline(self):
        rows = [self.step(1, 100, 1), self.control(21217, 1, storm=True), self.control(100, 1)]
        summary = self.summarize(rows, 1)
        self.assertEqual((summary["idleDecisionsPerSec"], summary["stormControlsExcluded"]), ({"core-1": 10.0}, 1))
        self.assertEqual(summary["passes"]["1"]["steps"][0]["decisionsPerSecNetOfIdle"], 90.0)

    def test_summary_noQuietControl_netOfIdleIsNone_andStepCarriesItsFlag(self):
        rows = [self.step(1, 100, 1, storm=True), self.control(21217, 1, storm=True)]
        step = self.summarize(rows, 1)["passes"]["1"]["steps"][0]
        self.assertEqual((step["decisionsPerSecNetOfIdle"], step["storm"]), (None, True))

    def test_pending_dump_control(self):
        self.assertEqual(armxk.pending_dump_control([{"agentCount": 0, "metricPendingBatches": 3}]), "fail")
        self.assertEqual(armxk.pending_dump_control([{"agentCount": 0, "metricPendingBatches": 3},
                                                     {"agentCount": 2, "metricPendingBatches": 0}]), "pass")
        self.assertEqual(armxk.pending_dump_control([{"agentCount": 0, "metricPendingBatches": 0}]), "inconclusive")
        self.assertEqual(armxk.pending_dump_control([]), "inconclusive")
        self.assertEqual(armxk.pending_dump_control([{"agentCount": 5, "metricPendingBatches": 5},
                                                     {"agentCount": None, "error": "HTTP 503", "metricPendingBatches": 0}]), "fail")

    def test_summary_recordsThePendingDumpControl(self):
        rows = [self.step(1, 100, 1, probe={"agentCount": 0, "metricPendingBatches": 4}), self.control(0, 1)]
        self.assertEqual(self.summarize(rows, 1)["pendingDumpControl"], "fail")


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

    def test_run_class_tagsEachAttemptsRecords_andClearsTheTag(self):
        with tempfile.TemporaryDirectory() as d:
            run = FakeRun(d)
            run.attempt = None
            seen = []

            def fn(r):
                seen.append(r.attempt)
                with open(r.dir / "evidence" / "storm.jsonl", "a") as f:
                    f.write("{}\n")
            armxk.run_class(run, "s0", fn, 1)
            self.assertEqual((seen, run.attempt), ([1, 2], None))

    def test_run_class_aborts_whenACoreDiesDuringTheClass(self):
        with tempfile.TemporaryDirectory() as d:
            run = FakeRun(d, dead_after=1)
            fn, calls = storming_class([0, 0])
            with self.assertRaises(armxk.HarnessError) as raised:
                armxk.run_class(run, "s6", fn, 1)
            self.assertIn("OutOfMemoryError", str(raised.exception))
            self.assertEqual((len(calls), self.pulled), (1, []))


class PastThePutControls(Exception):
    pass


class ControlSlotPathTest(unittest.TestCase):
    """The storm flag's numerator is the agent's slot counter; a dead slot hook reads 0 and would never flag."""

    def setUp(self):
        self.saved = armxk.measure

    def tearDown(self):
        armxk.measure = self.saved

    def control(self, slots, decisions=None):
        class R:
            cfg = {"reps": 1, "timing": {"controlMinMs": 1, "controlQuietMs": 1}}

            def leader(self):
                return "core-1"

            def node(self, cid):
                return {"id": cid}

            def nodes(self, role):
                raise PastThePutControls()

        rabia_cores = {cid: {**core(slots, 1, 0), "decisions": slots if decisions is None else decisions} for cid in ("core-1", "core-2")}
        armxk.measure = lambda run, cls, rep, action, **kw: {"perCore": {"core-1": {"put:ConfigKey": 1}, "core-2": {"put:ConfigKey": 1}},
                                                             "health": rabia_cores}
        armxk.control_class(R())

    def test_control_fails_whenThePutShowsNoSlot(self):
        with self.assertRaises(armxk.HarnessError) as raised:
            self.control(0)
        self.assertIn("dead slot path", str(raised.exception))

    def test_control_fails_whenOnlyTheSlotCounterIsDead(self):
        # Rabia decided the put (decisions 1) but the agent's slot hook counted nothing: the flag's numerator is dead
        with self.assertRaises(armxk.HarnessError):
            self.control(0, decisions=1)

    def test_control_passes_whenThePutShowsASlot(self):
        # the fake stops the class right after the put controls, where it moves on to the dead-counter readings
        with self.assertRaises(PastThePutControls):
            self.control(1)


class EmitTest(unittest.TestCase):
    def test_emit_tagsTheAttempt_onlyInsideOne(self):
        with tempfile.TemporaryDirectory() as d:
            run = armxk.Run.__new__(armxk.Run)
            run.dir = Path(d)
            (run.dir / "evidence").mkdir()
            run.attempt = 2
            run.emit("x", {"a": 1})
            run.attempt = None
            run.emit("x", {"a": 2})
            rows = [json.loads(line) for line in (run.dir / "evidence" / "x.jsonl").read_text().splitlines()]
            self.assertEqual(rows, [{"a": 1, "attempt": 2}, {"a": 2}])


class CheckCoresTest(unittest.TestCase):
    def run_with(self, answers):
        run = armxk.Run.__new__(armxk.Run)
        run.root = "/r"
        run.state = {"nodes": [{"id": cid, "host": "h", "role": "core", "alive": True} for cid in answers]}
        run.sh = lambda host, command, **kw: answers[command.split()[4]]
        return run

    def test_check_cores_passes_whenAllAlive(self):
        self.run_with({"core-1": "alive\n", "core-2": "alive\n"}).check_cores()

    def test_check_cores_aborts_onADeadCore(self):
        with self.assertRaises(armxk.HarnessError) as raised:
            self.run_with({"core-1": "alive\n", "core-2": "dead 1\n"}).check_cores()
        self.assertIn("core-2 process is gone after an OutOfMemoryError", str(raised.exception))

    def test_check_cores_aborts_whenTheHostDoesNotAnswer(self):
        with self.assertRaises(armxk.HarnessError) as raised:
            self.run_with({"core-1": ""}).check_cores()
        self.assertIn("liveness unreadable", str(raised.exception))


class MainFailurePathTest(unittest.TestCase):
    """Any exception, not only a HarnessError, pulls the evidence and exits non-zero (the cloud trap then tears down)."""

    def setUp(self):
        self.saved = (armxk.run_all, armxk.pull_evidence, sys.argv)
        self.pulled = []

    def tearDown(self):
        armxk.run_all, armxk.pull_evidence, sys.argv = self.saved

    def main(self, error, pull_fails=False):
        def pull(run, label):
            self.pulled.append(label)
            if pull_fails:
                raise armxk.HarnessError("evidence pull from h failed")

        def run_all(run):
            raise error
        armxk.run_all, armxk.pull_evidence = run_all, pull
        with tempfile.TemporaryDirectory() as d:
            Path(d, "inventory.json").write_text(json.dumps({"config": {}, "remoteRoot": "/r", "hosts": []}))
            sys.argv = ["armxk.py", "run-all", "--run-dir", d]
            with self.assertRaises(SystemExit) as exited:
                armxk.main()
        return exited.exception.code

    def test_main_pulls_onAnSshTimeout(self):
        import subprocess
        self.assertEqual((self.main(subprocess.TimeoutExpired("ssh", 120)), self.pulled), (1, ["on-failure"]))

    def test_main_pulls_onAHarnessError(self):
        self.assertEqual((self.main(armxk.HarnessError("core check failed")), self.pulled), (1, ["on-failure"]))

    def test_main_exitsNonZero_whenThePullFailsToo(self):
        self.assertEqual((self.main(RuntimeError("no leader"), pull_fails=True), self.pulled), (1, ["on-failure"]))


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

    def test_death_note_noAnswer_isNotAlive(self):
        self.assertEqual(self.note(""), "; node core-2 liveness unreadable (no answer from h)")


if __name__ == "__main__":
    unittest.main()
