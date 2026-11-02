import contextlib
import importlib.util
import io
import re
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

_spec = importlib.util.spec_from_file_location("check_quarantine", Path(__file__).with_name("check-quarantine.py"))
quarantine = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(quarantine)

ROOT_POM = """<project><properties><surefire.excludedGroups>Slow, quarantine</surefire.excludedGroups>
<failsafe.excludedGroups>quarantine</failsafe.excludedGroups></properties>
<build><pluginManagement><plugins>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId>
<configuration><excludedGroups>${failsafe.excludedGroups}</excludedGroups></configuration></plugin>
</plugins></pluginManagement></build>
<profiles><profile><build><plugins>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
<configuration><excludedGroups>${surefire.excludedGroups}</excludedGroups></configuration></plugin>
</plugins></build></profile></profiles></project>"""
AETHER_POM = """<project><build><pluginManagement><plugins>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId>
<configuration><excludedGroups>${failsafe.excludedGroups}</excludedGroups></configuration></plugin>
</plugins></pluginManagement></build></project>"""
CI = """jobs:
  forge-tests:
    steps:
      - run: mvn verify -Dfailsafe.excludedGroups=Heavy,quarantine
  quarantine:
    steps:
      - run: mvn verify -Dgroups=quarantine -Dsurefire.excludedGroups= -Dfailsafe.excludedGroups=
"""


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


class CheckQuarantineTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        write(self.root / "pom.xml", ROOT_POM)
        write(self.root / "aether/pom.xml", AETHER_POM)
        write(self.root / ".github/workflows/ci.yml", CI)

    def _test(self, module, name, body):
        write(self.root / module / "src/test/java/x" / f"{name}.java", body)

    def test_a_tag_with_its_issue_above_or_on_its_line_passes_and_names_its_module(self):
        self._test("a", "ATest", "class ATest {\n    // quarantine: #2562 times out on a loaded runner\n    @Test\n"
                                 "    @Tag(\"quarantine\")\n    void t() {}\n}\n")
        self._test("agg/b", "BTest", "@Tag(\"quarantine\") // #77, the whole class flakes\nclass BTest { @Test void t() {} }\n")
        self.assertEqual(quarantine.check(self.root), [])
        self.assertEqual([(p.name, line, issues) for p, line, issues, _ in quarantine.tags(self.root)],
                         [("ATest.java", 4, [2562]), ("BTest.java", 1, [77])])
        self.assertEqual(quarantine.quarantined_modules(self.root), ["a", "agg/b"])

    def test_a_tag_without_an_issue_fails(self):
        self._test("a", "ATest", "class ATest {\n    // flaky, see the chat\n\n    @Tag(\"quarantine\")\n    void t() {}\n}\n")
        self.assertEqual(quarantine.check(self.root), [
            "a/src/test/java/x/ATest.java:4: a quarantine tag needs its open issue: `// quarantine: #<issue> <why>` on the "
            "line above it"])

    def test_an_issue_beyond_a_blank_line_or_in_code_is_not_adjacent(self):
        self._test("a", "ATest", "class ATest {\n    // quarantine: #12\n\n    @Tag(\"quarantine\")\n    void t() {}\n}\n")
        self._test("a", "BTest", "class BTest {\n    int x = 1; // #12\n    @Tag(\"quarantine\")\n    void t() {}\n}\n")
        self.assertEqual(len(quarantine.check(self.root)), 2)

    def test_an_issue_number_in_an_annotation_string_is_not_a_reference(self):
        self._test("a", "ATest", 'class ATest {\n    @DisplayName("#12 retries")\n    @Tag("quarantine")\n    void t() {}\n}\n')
        self.assertEqual(len(quarantine.check(self.root)), 1)

    def test_check_reports_pom_and_workflow_exclusion_problems_too(self):
        write(self.root / "pom.xml", ROOT_POM.replace("Slow, quarantine", "Slow"))
        write(self.root / ".github/workflows/ci.yml", CI.replace("Heavy,quarantine", "Heavy"))
        self.assertEqual(quarantine.check(self.root), [
            "pom.xml: maven-surefire-plugin <excludedGroups>${surefire.excludedGroups}</excludedGroups> resolves to 'Slow', "
            "which does not exclude quarantine",
            "ci.yml job forge-tests: -Dfailsafe.excludedGroups=Heavy does not exclude quarantine"])

    def test_a_closed_issue_fails_and_only_referenced_issues_are_looked_up(self):
        self._test("a", "ATest", "class ATest {\n    // quarantine: #5 and #6\n    @Tag(\"quarantine\")\n    void t() {}\n}\n")
        asked = []
        problems = quarantine.check(self.root, is_open=lambda n: asked.append(n) or n == 5)
        self.assertEqual(asked, [5, 6])
        self.assertEqual(problems, ["a/src/test/java/x/ATest.java:3: #6 is not an open issue; fix the test and remove the "
                                    "tag, or cite the open issue that tracks it"])

    def test_a_non_canonical_spelling_fails(self):
        self._test("a", "ATest", "class ATest {\n    // #5\n    @Tag(value = \"Quarantine\")\n    void t() {}\n}\n")
        self.assertEqual(quarantine.check(self.root), ['a/src/test/java/x/ATest.java:3: write the quarantine tag exactly as '
                                                       '@Tag("quarantine")'])

    def test_build_output_is_not_scanned(self):
        write(self.root / "a/target/src/test/java/ATest.java", "@Tag(\"quarantine\") class ATest {}")
        self.assertEqual(quarantine.tags(self.root), [])

    def test_an_exclusion_without_quarantine_fails_after_property_resolution(self):
        write(self.root / "m/pom.xml", "<project><properties><heavyDb.excludedGroups>heavy-db</heavyDb.excludedGroups>"
                                       "</properties><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>"
                                       "<artifactId>maven-surefire-plugin</artifactId><configuration>"
                                       "<excludedGroups>${heavyDb.excludedGroups}</excludedGroups></configuration></plugin>"
                                       "</plugins></build></project>")
        write(self.root / "n/pom.xml", "<project><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>"
                                       "<artifactId>maven-failsafe-plugin</artifactId><configuration>"
                                       "<excludedGroups>${failsafe.excludedGroups}</excludedGroups></configuration></plugin>"
                                       "</plugins></build></project>")  # resolves through the root's property: passes
        self.assertEqual(quarantine.exclusion_problems(self.root), [
            "m/pom.xml: maven-surefire-plugin <excludedGroups>${heavyDb.excludedGroups}</excludedGroups> resolves to "
            "'heavy-db', which does not exclude quarantine"])

    def test_a_group_named_like_quarantine_is_not_quarantine(self):
        write(self.root / "m/pom.xml", "<project><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>"
                                       "<artifactId>maven-surefire-plugin</artifactId><configuration>"
                                       "<excludedGroups>quarantined, slow</excludedGroups></configuration></plugin>"
                                       "</plugins></build></project>")
        self.assertEqual(len(quarantine.exclusion_problems(self.root)), 1)

    def test_a_failsafe_parent_without_an_exclusion_fails(self):
        write(self.root / "aether/pom.xml", AETHER_POM.replace("<excludedGroups>${failsafe.excludedGroups}</excludedGroups>", ""))
        self.assertEqual(quarantine.exclusion_problems(self.root), [
            "aether/pom.xml: pluginManagement maven-failsafe-plugin has no <excludedGroups>, so its users run quarantined ITs"])

    def test_a_required_job_override_must_keep_quarantine_and_the_quarantine_job_may_clear_it(self):
        self.assertEqual(quarantine.workflow_problems(CI), [])
        self.assertEqual(quarantine.workflow_problems(CI.replace("Heavy,quarantine", "Heavy")),
                         ["ci.yml job forge-tests: -Dfailsafe.excludedGroups=Heavy does not exclude quarantine"])
        self.assertEqual(quarantine.workflow_problems(CI.replace("  quarantine:", "  heavy:")), [
            "ci.yml job heavy: -Dsurefire.excludedGroups= does not exclude quarantine",
            "ci.yml job heavy: -Dfailsafe.excludedGroups= does not exclude quarantine"])

    def test_the_check_cli_exits_non_zero_on_a_problem_and_zero_without(self):
        err = io.StringIO()
        with mock.patch.object(quarantine, "ROOT", self.root), contextlib.redirect_stderr(err):
            quarantine.main(["check"])
            self.assertIn("quarantine: 0 tag(s) in 0 module(s), 0 problem(s)", err.getvalue())
            self._test("a", "ATest", "class ATest {\n    @Tag(\"quarantine\")\n    void t() {}\n}\n")
            with self.assertRaises(SystemExit) as raised:
                quarantine.main(["check"])
        self.assertIn("a quarantine tag needs its open issue", str(raised.exception))

    def test_the_check_cli_with_a_repo_looks_each_issue_up(self):
        self._test("a", "ATest", "class ATest {\n    // #9\n    @Tag(\"quarantine\")\n    void t() {}\n}\n")
        with mock.patch.object(quarantine, "ROOT", self.root), mock.patch.object(quarantine, "open_issue",
                                                                                 lambda repo, n: repo == "o/r" and n != 9), \
                contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            quarantine.main(["check", "--repo", "o/r"])
        self.assertIn("#9 is not an open issue", str(raised.exception))

    def test_the_modules_cli_prints_the_tagged_modules_or_nothing(self):
        out = io.StringIO()
        with mock.patch.object(quarantine, "ROOT", self.root), contextlib.redirect_stdout(out):
            quarantine.main(["modules"])
            self._test("agg/b", "BTest", "// #3\n@Tag(\"quarantine\")\nclass BTest {}\n")
            quarantine.main(["modules"])
        self.assertEqual(out.getvalue(), "\nagg/b\n")


class RepositoryQuarantineTest(unittest.TestCase):
    CI_TEXT = (quarantine.ROOT / ".github/workflows/ci.yml").read_text()

    def _job(self, name):
        return re.search(rf"^  {name}:\n(.*?)(?=^  \S|\Z)", self.CI_TEXT, re.MULTILINE | re.DOTALL).group(1)

    def test_the_repository_passes_the_guard(self):
        # guards the guard: the scan reaches the real poms (the root's surefire exclusion is among them)
        self.assertTrue(any(p.name == "pom.xml" for p in quarantine.ROOT.glob("pom.xml")))
        self.assertEqual(quarantine.check(), [])

    def test_the_quarantine_job_never_gates(self):
        self.assertNotIn("quarantine", re.search(r"needs: \[([^\]]*)\]", self._job("build-and-test")).group(1))
        self.assertNotIn("needs:", self._job("quarantine"))

    def test_the_quarantine_job_selects_the_tag_and_clears_every_exclusion(self):
        job = self._job("quarantine")
        for flag in ("-Dgroups=quarantine", "-Dsurefire.excludedGroups=", "-Dfailsafe.excludedGroups=",
                     "-DheavyDb.excludedGroups=", "-DheavyDb.includedGroups=quarantine", "-Dpostgres-async.excludedGroups=",
                     "-Dember.skipTests=false", "-Dconsensus.skipTests=false", "-Dpostgres-async.skipTests=false"):
            self.assertIn(flag, job)
        self.assertIn('modules=$(python3 -B tools/check-quarantine.py modules)', job)

    def test_static_gates_runs_the_guard_against_live_issue_state(self):
        self.assertIn('python3 -B tools/check-quarantine.py check --repo "${{ github.repository }}"', self._job("static-gates"))


if __name__ == "__main__":
    unittest.main()
