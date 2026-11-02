#!/usr/bin/env python3
"""The idle-storm flag's rule, pinned without a cluster: `python3 aether/tests/cloud/armxk/lib/test_storm.py`."""
import sys
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


if __name__ == "__main__":
    unittest.main()
