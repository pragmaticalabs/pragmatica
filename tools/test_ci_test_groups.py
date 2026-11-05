import contextlib
import importlib.util
import io
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

_tools = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("ci_test_groups", _tools / "ci-test-groups.py")
groups = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(groups)
_spec = importlib.util.spec_from_file_location("compare_testcases", _tools / "compare-testcases.py")
compare = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(compare)

TEST = "class ATest { @Test void t() {} }"
CI = """jobs:
  test-group:
    strategy:
      matrix:
        group: [g1, g2]
  ember-shard:
    runs-on: x
"""


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def pom(*modules, profile=""):
    listed = "".join(f"<module>{m}</module>" for m in modules)
    return f"<project><modules>{listed}<!-- <module>commented</module> --></modules>{profile}</project>"


def report(root, module, kind, name, *cases, tests=None):
    body = "".join(f'<testcase classname="{c}" name="{n}">{"<skipped/>" if s else ""}</testcase>' for c, n, s in cases)
    count = len(cases) if tests is None else tests
    write(root / module / "target" / kind / f"TEST-{name}.xml", f'<testsuite name="{name}" time="1.5" tests="{count}">{body}</testsuite>')


class CiTestGroupsTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        write(self.root / "pom.xml", pom("a", "agg", "ember", "nested-profile-free",
                                         profile="<profiles><profile><modules><module>e2e</module></modules></profile></profiles>"))
        write(self.root / "agg/pom.xml", pom("b", "c"))
        for module in ("a", "agg/b", "ember", "e2e"):
            write(self.root / module / "pom.xml", pom())
            write(self.root / module / "src/test/java/ATest.java", TEST)
        write(self.root / "agg/c/pom.xml", pom())
        write(self.root / "agg/c/src/test/java/TestSerializers.java", "class TestSerializers {}")  # a fixture, no @Test
        # @Test methods in a class surefire's and failsafe's names never select: an abstract contract, not a test
        write(self.root / "agg/c/src/test/java/ContractSpec.java", "abstract class ContractSpec { @Test void t() {} }")
        write(self.root / "nested-profile-free/pom.xml", pom())
        write(self.root / "nested-profile-free/src/test/java/LoaderIT.java", "class LoaderIT { @Test void t() {} }")
        self.groups = self.root / "groups.txt"
        self.ci = self.root / "ci.yml"
        self.ci.write_text(CI)

    def _check(self, rows):
        self.groups.write_text(rows)
        return groups.check(self.root, self.groups, self.ci)

    def test_a_module_whose_every_class_is_quarantined_has_no_tests(self):
        # a required job runs nothing there and the module writes no report, so `check` must stop listing it
        write(self.root / "a/src/test/java/ATest.java", '// quarantine: #1\n@Tag("quarantine")\nclass ATest { @Test void t() {} }')
        self.assertNotIn("a", groups.modules_with_tests(self.root))
        write(self.root / "a/src/test/java/ATest.java", 'class ATest {\n    // quarantine: #1\n    @Tag("quarantine")\n'
                                                         '    @Test void t() {}\n}')
        self.assertIn("a", groups.modules_with_tests(self.root))  # a method-level tag leaves the class's other tests

    def test_a_root_with_tests_needs_an_owner_too(self):
        write(self.root / "src/test/java/ATest.java", TEST)
        self.assertIn("", groups.modules_with_tests(self.root))

    def test_reactor_is_read_through_aggregators_without_profiles_or_comments(self):
        self.assertEqual(groups.reactor_modules(self.root), ["", "a", "agg", "agg/b", "agg/c", "ember", "nested-profile-free"])
        # agg/c has only a fixture; an IT-only module has tests
        self.assertEqual(groups.modules_with_tests(self.root), ["a", "agg/b", "ember", "nested-profile-free"])

    def test_a_profile_ahead_of_the_default_modules_is_not_read_as_the_reactor(self):
        # the first <modules> in the text is the profile's: only stripping <profiles> leaves the default block first
        write(self.root / "agg/pom.xml", "<project><profiles><profile><modules><module>gated</module></modules></profile>"
                                         "</profiles><modules><module>b</module><module>c</module></modules></project>")
        self.assertEqual(groups.reactor_modules(self.root), ["", "a", "agg", "agg/b", "agg/c", "ember", "nested-profile-free"])

    def test_every_module_with_tests_owned_once_passes(self):
        self.assertEqual(self._check("g1 a\ng2 agg/b  # 1.5 s at run 1\ng2 nested-profile-free\njob:ember-shard ember\n"), [])

    def test_an_unowned_module_with_tests_fails(self):
        problems = self._check("g1 a\ng2 agg/b\njob:ember-shard ember\n")
        self.assertEqual(problems, ["nested-profile-free has tests and no owner: add `<group> nested-profile-free` to groups.txt"])

    def test_a_module_owned_twice_fails(self):
        problems = self._check("g1 a\ng2 a\ng2 agg/b\ng2 nested-profile-free\njob:ember-shard ember\n")
        self.assertEqual(problems, ["a has 2 owners (g1, g2): it would run twice"])

    def test_a_listed_module_without_tests_or_outside_the_reactor_fails(self):
        problems = self._check("g1 a\ng1 agg/c\ng1 e2e\ng2 agg/b\ng2 nested-profile-free\njob:ember-shard ember\n")
        self.assertEqual(problems, ["agg/c is listed but is not a default-reactor module with tests",
                                    "e2e is listed but is not a default-reactor module with tests"])

    def test_matrix_must_name_exactly_the_groups(self):
        problems = self._check("g1 a\ng1 agg/b\ng1 nested-profile-free\njob:ember-shard ember\n")
        self.assertEqual(problems, ["ci.yml test-group matrix ['g1', 'g2'] differs from the groups in groups.txt ['g1']"])

    def test_a_job_owner_must_be_a_ci_job(self):
        problems = self._check("g1 a\ng2 agg/b\ng2 nested-profile-free\njob:consensus-shard ember\n")
        self.assertEqual(problems, ["job:consensus-shard names no ci.yml job"])

    def test_examples_modules_must_share_one_group_and_maven_test_skip_is_refused(self):
        write(self.root / "pom.xml", pom("a", "agg", "ember", "nested-profile-free", "examples"))
        write(self.root / "examples/pom.xml", pom("x", "y"))
        for module in ("examples/x", "examples/y"):
            write(self.root / module / "pom.xml", pom())
            write(self.root / module / "src/test/java/ATest.java", TEST)
        self.ci.write_text(CI + "      - run: mvn install -Dmaven.test.skip=true\n")
        problems = self._check("g1 a\ng1 examples/x\ng2 examples/y\ng2 agg/b\ng2 nested-profile-free\njob:ember-shard ember\n")
        self.assertEqual(problems, [
            "examples modules are split across ['g1', 'g2']: the standalone examples build needs one group",
            "ci.yml uses maven.test.skip, which drops the statemachine test-jar that five modules test-depend on"])

    def test_verify_reports_fails_a_grouped_module_without_reports_and_ignores_job_owned_ones(self):
        rows = [("g1", "a"), ("g2", "agg/b"), ("job:ember-shard", "ember")]
        reports = self.root / "reports"
        report(reports, "a", "surefire-reports", "x.ATest", ("x.ATest", "t", False), ("x.ATest", "u", False))
        report(reports, "a", "failsafe-reports", "x.AIT", ("x.AIT", "t", False))
        problems, totals = groups.verify_reports(reports, rows)
        self.assertEqual(problems, ["agg/b (group g2) wrote no TEST-*.xml: its tests did not run"])
        self.assertEqual(totals, {"g1": (1, 3), "g2": (1, 0)})

    def test_regenerate_balances_by_measured_time_and_keeps_examples_together(self):
        # names sort against their weights, so name order would give [135, 90], not LPT's [125, 100]
        rows = [("g1", m) for m in ("z-big", "m-mid", "a-small", "examples/x", "examples/y")] + [("job:ember-shard", "ember")]
        seconds = {"z-big": 100.0, "m-mid": 60.0, "a-small": 30.0, "examples/x": 20.0, "examples/y": 15.0}
        text, totals = groups.regenerate(rows, seconds, 2, "7")
        self.groups.write_text(text)
        regenerated = groups.read_groups(self.groups)
        self.assertEqual(totals, [125.0, 100.0])
        self.assertEqual(sorted(regenerated), sorted([("g1", "m-mid"), ("g1", "examples/x"), ("g1", "examples/y"),
                                                      ("g1", "a-small"), ("g2", "z-big"), ("job:ember-shard", "ember")]))
        self.assertIn("g2 z-big  # 100.0 s at run 7\n", text)

    def test_module_seconds_sums_surefire_and_failsafe_per_module(self):
        reports = self.root / "reports"
        report(reports, "agg/b", "surefire-reports", "x.ATest", ("x.ATest", "t", False))
        report(reports, "agg/b", "failsafe-reports", "x.AIT", ("x.AIT", "t", False))
        self.assertEqual(groups.module_seconds(reports), {"agg/b": 3.0})


    def test_module_seconds_exits_naming_a_report_without_a_time(self):
        reports = self.root / "reports"
        write(reports / "agg/b/target/surefire-reports/TEST-x.ATest.xml", '<testsuite name="x.ATest" tests="1"/>')
        with self.assertRaises(SystemExit) as raised:
            groups.module_seconds(reports)
        self.assertIn("TEST-x.ATest.xml: <testsuite> has no time attribute", str(raised.exception))


class RepositoryGroupsTest(unittest.TestCase):
    def test_the_repository_grouping_is_sound(self):
        # guards the guard: the real reactor has over a hundred modules with tests, and node among them
        tested = groups.modules_with_tests()
        self.assertGreaterEqual(len(tested), 100)
        self.assertIn("aether/node", tested)
        self.assertEqual(groups.check(), [])

    def test_the_ci_groups_run_the_statemachine_test_jar_consumers_from_an_install(self):
        ci = groups.CI.read_text()
        self.assertIn('mvn install -B -DskipTests -pl "${{ steps.plan.outputs.modules }}" -am', ci)
        self.assertIn("needs: [push-dedupe, static-gates, test-group]", ci)
        self.assertIn("python3 -B tools/ci-test-groups.py verify-reports reports", ci)


    def _cli(self, *argv):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            groups.main(list(argv))
        return out.getvalue().strip()

    def test_the_ci_group_modules_command_lists_only_its_group(self):
        rows = groups.read_groups()
        for group in ("g1", "g2", "g3", "g4"):
            self.assertEqual(self._cli("modules", "--group", group).split(","), [m for o, m in rows if o == group])
        self.assertEqual(self._cli("modules", "--group", "g1"), "aether/node")
        with self.assertRaises(SystemExit):
            self._cli("modules", "--group", "job:ember-shard")  # not a group: its module is the shard job's
        with self.assertRaises(SystemExit):
            self._cli("modules", "--group", "g9")

    def test_the_check_command_exits_non_zero_naming_every_problem(self):
        # the CI step is the exit status: a check that printed its problems and exited 0 would pass an unsound grouping
        with mock.patch.object(groups, "check", return_value=["x has tests and no owner", "y has 2 owners"]):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
                self._cli("check")
        self.assertEqual(str(raised.exception), "x has tests and no owner\ny has 2 owners")

    def test_the_verify_reports_command_exits_non_zero_on_a_module_without_reports(self):
        with tempfile.TemporaryDirectory() as empty:
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
                self._cli("verify-reports", empty)
        self.assertIn("aether/node (group g1) wrote no TEST-*.xml: its tests did not run", str(raised.exception))

    def test_exactly_one_group_runs_the_standalone_examples_build(self):
        answers = {g: self._cli("has-examples", "--group", g) for g in ("g1", "g2", "g3", "g4")}
        self.assertEqual(sorted(answers.values()), ["no", "no", "no", "yes"], answers)

    def test_the_aggregate_fails_unless_every_needed_job_passed(self):
        job = groups.CI.read_text().split("\n  build-and-test:\n", 1)[1].split("\n  forge-tests:\n", 1)[0]
        self.assertIn("    if: always() && needs.push-dedupe.outputs.skip != 'true'\n", job)  # always() without it a failed group SKIPS the required check, which passes
        self.assertIn('[ "${{ needs.static-gates.result }}" = success ] && [ "${{ needs.test-group.result }}" = success ]', job)

    def test_a_skipped_duplicate_push_run_never_posts_a_required_context_name(self):
        # a skipped check run named like a required context satisfies it (seen on #2657); the single-instance required jobs rename
        # themselves when push-dedupe skips them, and the matrix jobs' unexpanded name template matches no required context
        ci = groups.CI.read_text()
        for name in ("build-and-test", "forge-tests", "stub-suites"):
            job = ci.split(f"\n  {name}:\n", 1)[1].split("\n\n", 1)[0]
            self.assertIn(f"    name: ${{{{ needs.push-dedupe.outputs.skip == 'true' && '{name} (duplicate push run, skipped)' || '{name}' }}}}\n", job)
        for name in ("test-group", "ember-shard", "consensus-shard"):
            self.assertIn(f"    name: {name}-${{{{ matrix.", ci.split(f"\n  {name}:\n", 1)[1].split("\n\n", 1)[0])

    def test_the_group_job_keeps_build_and_tests_test_flags(self):
        job = groups.CI.read_text().split("\n  test-group:\n", 1)[1].split("\n  build-and-test:\n", 1)[0]
        self.assertIn("-Dember.skipTests=true -Dconsensus.skipTests=true -Dpostgres-async.skipTests=false", job)
        self.assertIn("if: steps.plan.outputs.examples == 'yes'\n        run: mvn verify -B -f examples/pom.xml", job)
        self.assertIn("test-group-${{ matrix.group }}.txt\n            **/target/surefire-reports/*.xml", job)
        self.assertIn("TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX: mirror.gcr.io/", job)


class CompareTestcasesTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        self.old, self.new = self.root / "old", self.root / "new"
        for side in (self.old, self.new):
            report(side, "a", "surefire-reports", "x.ATest", ("x.ATest", "p", False), ("x.ATest", "p", False),
                   ("x.ATest", "s", True), tests=0)
            report(side, "agg/b", "failsafe-reports", "x.BIT", ("x.BIT", "t", False))

    def _run(self, *options):
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            try:
                compare.main([*options, str(self.old), str(self.new)])
                return None, err.getvalue()
            except SystemExit as e:
                return str(e), err.getvalue()

    def test_equal_sides_pass_and_both_counts_are_printed(self):
        failure, err = self._run()
        self.assertIsNone(failure)
        self.assertIn("old: 2 report file(s), 4 testcase element(s), 3 distinct, 1 skipped; tests= attribute sum 1", err)

    def test_a_lost_repeat_of_a_parameterized_name_fails(self):
        report(self.new, "a", "surefire-reports", "x.ATest", ("x.ATest", "p", False), ("x.ATest", "s", True))
        failure, err = self._run()
        self.assertEqual(failure, f"1 testcase(s) differ between {self.old} and {self.new}")
        self.assertIn("a x.ATest#p: old 2, new 1", err)

    def test_a_missing_failsafe_report_fails(self):
        (self.new / "agg/b/target/failsafe-reports/TEST-x.BIT.xml").unlink()
        failure, err = self._run()
        self.assertIsNotNone(failure)
        self.assertIn("agg/b x.BIT#t: old 1, new 0", err)

    def test_a_test_that_became_skipped_fails(self):
        report(self.new, "agg/b", "failsafe-reports", "x.BIT", ("x.BIT", "t", True))
        failure, _ = self._run()
        self.assertIsNotNone(failure)

    def test_module_option_reads_flat_per_shard_artifacts_at_any_depth(self):
        # ember-test-results-N holds TEST-*.xml at its top level; a download of several lands one directory per shard
        flat_old, flat_new = self.root / "flat-old", self.root / "flat-new"
        write(flat_old / "ember-test-results-1/TEST-x.ATest.xml", '<testsuite tests="2"><testcase classname="x.ATest" name="a"/>'
                                                                  '<testcase classname="x.ATest" name="a"/></testsuite>')
        write(flat_old / "ember-test-results-2/TEST-x.BTest.xml", '<testsuite tests="1"><testcase classname="x.BTest" name="b"/></testsuite>')
        write(flat_new / "ember-test-results-3/TEST-x.ATest.xml", '<testsuite tests="2"><testcase classname="x.ATest" name="a"/>'
                                                                  '<testcase classname="x.ATest" name="a"/></testsuite>')
        write(flat_new / "ember-test-results-4/TEST-x.BTest.xml", '<testsuite tests="1"><testcase classname="x.BTest" name="b"/></testsuite>')
        cases, files, _ = compare.testcases(flat_old, "aether/ember")
        self.assertEqual((files, cases[("aether/ember", "x.ATest", "a", False)]), (2, 2))
        self.old, self.new = flat_old, flat_new
        failure, err = self._run("--module", "aether/ember")
        self.assertIsNone(failure)
        self.assertIn("old: 2 report file(s), 3 testcase element(s)", err)
        (flat_new / "ember-test-results-4/TEST-x.BTest.xml").unlink()  # a class lost in the reshard
        failure, err = self._run("--module", "aether/ember")
        self.assertIsNotNone(failure)
        self.assertIn("aether/ember x.BTest#b: old 1, new 0", err)

    def test_without_module_a_flat_artifact_is_refused_not_compared_as_empty(self):
        write(self.root / "flat/TEST-x.ATest.xml", '<testsuite tests="1"><testcase classname="x.ATest" name="a"/></testsuite>')
        self.old = self.new = self.root / "flat"
        failure, _ = self._run()
        self.assertIn("old: no TEST-*.xml under", failure)

    def test_a_side_compared_with_itself_or_its_own_subdirectory_is_refused(self):
        for old, new in ((self.old, self.old), (self.root, self.old), (self.old, self.root)):
            self.old, self.new = old, new
            failure, _ = self._run("--module", "m")
            self.assertIn("overlap: download each run into its own directory", failure or "")

    def test_an_empty_side_is_refused(self):
        shutil.rmtree(self.new)
        self.new.mkdir()
        failure, _ = self._run()
        self.assertIn("new: no TEST-*.xml", failure)


if __name__ == "__main__":
    unittest.main()
