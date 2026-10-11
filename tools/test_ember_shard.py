import importlib.util
import tempfile
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location("ember_shard", Path(__file__).with_name("ember-shard.py"))
shard = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(shard)


class EmberShardTest(unittest.TestCase):
    def setUp(self):
        self.classes = shard.test_classes()
        self.weights = shard.read_weights()

    def test_source_tree_is_read(self):
        # guards the guard: a wrong TEST_DIR would make every other assertion vacuously true
        self.assertGreaterEqual(len(self.classes), 30)
        self.assertIn("EmberNodeReplacementTest", self.classes)

    def test_every_class_is_in_exactly_one_shard_for_any_shard_count(self):
        for n in range(1, 7):
            bins = shard.assign(self.classes, self.weights, n)
            flat = [c for b in bins for c in b["classes"]]
            self.assertEqual(sorted(flat), self.classes, f"shards={n}")
            self.assertEqual(len(flat), len(set(flat)), f"shards={n}: a class is in two shards")

    def test_class_missing_from_the_weights_file_is_still_run(self):
        weights = {k: v for k, v in self.weights.items() if k != "EmberNodeReplacementTest"}
        bins = shard.assign(self.classes, weights, 2)
        self.assertIn("EmberNodeReplacementTest", [c for b in bins for c in b["classes"]])

    def test_unknown_class_costs_the_largest_known_weight(self):
        bins = shard.assign(self.classes + ["EmberBrandNewTest"], self.weights, 2)
        placed = next(b for b in bins if "EmberBrandNewTest" in b["classes"])
        self.assertGreaterEqual(placed["total"], shard.default_weight(self.weights))

    def test_current_split_is_within_the_budget_and_balanced(self):
        totals = [b["total"] for b in shard.assign(self.classes, self.weights, 2)]
        self.assertLess(max(totals), shard.BUDGET_SECONDS)
        self.assertLess(max(totals) - min(totals), 0.1 * max(totals))

    def test_verify_fails_on_zero_reports_and_on_a_missing_class(self):
        with tempfile.TemporaryDirectory() as d:
            missing, n = shard.verify(d, ["EmberATest"])
            self.assertEqual((missing, n), (["EmberATest"], 0))
            (Path(d) / "TEST-org.x.EmberATest.xml").write_text("<testsuite/>")
            (Path(d) / "TEST-org.x.EmberBTest$Inner.xml").write_text("<testsuite/>")
            self.assertEqual(shard.verify(d, ["EmberATest", "EmberBTest"])[0], [])
            self.assertEqual(shard.verify(d, ["EmberATest", "EmberCTest"])[0], ["EmberCTest"])


if __name__ == "__main__":
    unittest.main()
