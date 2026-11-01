import importlib.util
import re
import tempfile
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location("ember_shard", Path(__file__).with_name("ember-shard.py"))
shard = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(shard)

TEST_DIR, WEIGHTS, BUDGET = shard.MODULES["consensus"]
CI = Path(__file__).resolve().parent.parent / ".github/workflows/ci.yml"


def matrix_shards():
    """The consensus-shard job's matrix length, read from ci.yml: the plan CI actually runs."""
    job = re.search(r"^  consensus-shard:\n(.*?)(?=^  \S|\Z)", CI.read_text(), re.MULTILINE | re.DOTALL).group(1)
    return len(re.search(r"shard: \[([^\]]*)\]", job).group(1).split(","))


class ConsensusShardTest(unittest.TestCase):
    def setUp(self):
        self.classes = shard.test_classes(TEST_DIR)
        self.weights = shard.read_weights(WEIGHTS)

    def test_source_tree_is_read(self):
        # guards the guard: a wrong test dir would make every other assertion vacuously true
        self.assertGreaterEqual(len(self.classes), 100)
        self.assertIn("QuicSimultaneousDialLaneTest", self.classes)

    def test_fixtures_and_excluded_groups_are_not_planned(self):
        self.assertNotIn("TestSerializers", self.classes)
        self.assertNotIn("RabiaPerformanceTest", self.classes)

    def test_every_class_is_in_exactly_one_shard_for_any_shard_count(self):
        for n in range(1, 7):
            flat = [c for b in shard.assign(self.classes, self.weights, n) for c in b["classes"]]
            self.assertEqual(sorted(flat), self.classes, f"shards={n}")

    def test_ci_matrix_plan_covers_every_class_once(self):
        n = matrix_shards()
        self.assertGreaterEqual(n, 1)
        flat = [c for b in shard.assign(self.classes, self.weights, n) for c in b["classes"]]
        self.assertEqual(sorted(flat), self.classes, f"ci.yml consensus-shard matrix of {n}")

    def test_ci_matrix_plan_is_within_the_budget(self):
        totals = [b["total"] for b in shard.assign(self.classes, self.weights, matrix_shards())]
        self.assertLess(max(totals), BUDGET)

    def test_a_planned_class_without_a_weight_is_planned_at_the_default(self):
        # #2414 required a weight line per class; that made the weights file a merge hotspot, so a new class now plans
        unweighted = {k: v for k, v in self.weights.items() if k != "QuicSimultaneousDialLaneTest"}
        bins = shard.assign(self.classes, unweighted, matrix_shards())
        self.assertEqual(sorted(c for b in bins for c in b["classes"]), self.classes)
        self.assertEqual(sum(b["total"] for b in bins),
                         sum(unweighted.values()) + shard.default_weight(unweighted))

    def test_verify_fails_on_zero_reports_and_on_a_missing_class(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(shard.verify(d, ["XTest"]), (["XTest"], 0))
            (Path(d) / "TEST-org.x.XTest.xml").write_text("<testsuite/>")
            self.assertEqual(shard.verify(d, ["XTest"])[0], [])


if __name__ == "__main__":
    unittest.main()
