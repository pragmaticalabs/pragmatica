"""Regression checks for absent classes and misleading partial/empty Forge reports."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("hierarchy_selection", Path(__file__).with_name("check-hierarchy-selection.py"))
selection = importlib.util.module_from_spec(spec)
spec.loader.exec_module(selection)


class HierarchySelectionTest(unittest.TestCase):
    def test_missing_one_selected_source_refuses_before_build(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / selection.TEST_DIRECTORY / "PresentTest.java"
            source.parent.mkdir(parents=True)
            source.touch()
            with self.assertRaisesRegex(ValueError, "AbsentTest"):
                selection.verify_sources(root, ["PresentTest", "AbsentTest"])

    def verify(self, body, names):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory)
            (reports / "TEST-sample.xml").write_text('<testsuite tests="999">' + body + '</testsuite>')
            selection.verify_reports(reports, names)

    def test_partial_green_report_does_not_cover_another_selected_class(self):
        with self.assertRaisesRegex(ValueError, "AbsentTest"):
            self.verify('<testcase classname="org.pragmatica.aether.forge.PresentTest"/>',
                        ["PresentTest", "AbsentTest"])

    def test_declared_count_without_executed_cases_is_not_evidence(self):
        with self.assertRaisesRegex(ValueError, "PresentTest"):
            self.verify('', ["PresentTest"])

    def test_nested_cases_cover_the_selected_outer_class(self):
        self.verify('<testcase classname="org.pragmatica.aether.forge.PresentTest$Nested"/>', ["PresentTest"])

    def test_skipped_or_failed_case_is_not_a_complete_pass(self):
        for tag in ("skipped", "failure", "error"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                self.verify('<testcase classname="org.pragmatica.aether.forge.PresentTest"/>'
                            '<testcase classname="org.pragmatica.aether.forge.PresentTest"><' + tag + '/></testcase>',
                            ["PresentTest"])

    def test_unrelated_package_cannot_satisfy_selected_class(self):
        with self.assertRaises(ValueError):
            self.verify('<testcase classname="other.PresentTest"/>', ["PresentTest"])

    def test_empty_wildcard_method_and_duplicate_selections_are_rejected(self):
        for selector in ("", "Present*", "PresentTest#method", "PresentTest,", "PresentTest,PresentTest"):
            with self.subTest(selector=selector), self.assertRaises(ValueError):
                selection.selected_classes(selector)


if __name__ == "__main__":
    unittest.main()
