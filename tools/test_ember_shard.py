import contextlib
import importlib.util
import io
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

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

    def test_unknown_class_costs_the_75th_percentile_weight(self):
        self.assertEqual(shard.default_weight({"A": 1, "B": 2, "C": 3, "D": 4, "E": 100}), 4)
        self.assertEqual(shard.default_weight({"A": 0, "B": 5}), 4)  # 3.75 rounded up
        bins = shard.assign(self.classes + ["EmberBrandNewTest"], self.weights, 2)
        placed = next(b for b in bins if "EmberBrandNewTest" in b["classes"])
        self.assertGreaterEqual(placed["total"], shard.default_weight(self.weights))
        self.assertLess(shard.default_weight(self.weights), max(self.weights.values()))

    def test_weight_line_may_carry_a_trailing_provenance_comment(self):
        with tempfile.TemporaryDirectory() as d:
            w = Path(d) / "w.txt"
            w.write_text("# header\nATest 13  # measured at run 1\nBTest 2\n")
            self.assertEqual(shard.read_weights(w), {"ATest": 13, "BTest": 2})

    def test_current_split_is_within_the_budget_and_balanced(self):
        totals = [b["total"] for b in shard.assign(self.classes, self.weights, 3)]
        self.assertLess(max(totals), shard.BUDGET_SECONDS)
        self.assertLess(max(totals) - min(totals), 0.1 * max(totals))

    def _plan(self, budget, weights_text, *extra, index=1):
        """main() over a synthetic module: three cluster-sized classes plus `extra`, the given weights and budget."""
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d)
        src = Path(d) / "src"
        src.mkdir()
        for c in ("EmberATest", "EmberBTest", "EmberCTest") + extra:
            (src / f"{c}.java").write_text(f"class {c} {{ @Test void t() {{}} }}")
        weights = Path(d) / "w.txt"
        weights.write_text(weights_text)
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.dict(shard.MODULES, {"synthetic": (src, weights, budget)}), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            shard.main(["--module", "synthetic", "--shards", "2", "--index", str(index)])
        return out.getvalue(), err.getvalue()

    def test_plan_succeeds_with_a_new_class_that_has_no_weight(self):
        # under the old maximum default the new class cost 100 s, so a shard (100+60) was predicted over the 150 s budget
        weights = "EmberATest 100\nEmberBTest 60\nEmberCTest 40\n"
        (first, err), (second, _) = (self._plan(150, weights, "EmberBrandNewTest", index=i) for i in (1, 2))
        self.assertIn("unweighted classes (default 80 s each): EmberBrandNewTest", err)
        self.assertEqual(sorted(first.strip().split(",") + second.strip().split(",")),
                         ["EmberATest", "EmberBTest", "EmberBrandNewTest", "EmberCTest"])

    def test_plan_fails_when_a_shard_is_predicted_over_budget(self):
        with self.assertRaises(SystemExit) as raised:
            self._plan(99, "EmberATest 100\nEmberBTest 60\nEmberCTest 40\n")
        self.assertIn("predicted over", str(raised.exception))
        with self.assertRaises(SystemExit) as raised:
            self._plan(130, "EmberATest 100\nEmberBTest 60\nEmberCTest 40\n", "EmberBrandNewTest")
        self.assertIn("1 class(es) at the default weight", str(raised.exception))

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
