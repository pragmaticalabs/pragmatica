"""Pins for tools/check-ci-test-images.py and tools/ci-prepull-images.sh (#3124)."""
import importlib.util
import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("check_ci_test_images", TOOLS / "check-ci-test-images.py")
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)

TEST_SRC = 'import org.testcontainers.containers.PostgreSQLContainer;\nclass T { PostgreSQLContainer<?> c = new PostgreSQLContainer<>("postgres:16"); }\n'
POM = "<properties><testcontainers.version>1.21.4</testcontainers.version></properties>"


def tree(root, sources, listing):
    (root / "tools").mkdir(parents=True)
    (root / "tools/ci-test-groups.txt").write_text("g1 mod/a  # 1 s\n")
    (root / "tools/ci-test-images.txt").write_text(listing)
    (root / "pom.xml").write_text(POM)
    for rel, text in sources.items():
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)


GOOD_LIST = "testcontainers/ryuk:0.12.0 *\npostgres:16 mod/a\n"
SOURCES = {"mod/a/src/test/java/T.java": TEST_SRC}


class CheckTest(unittest.TestCase):
    def run_check(self, sources, listing):
        with tempfile.TemporaryDirectory() as tmp:
            tree(Path(tmp), sources, listing)
            return check.problems(Path(tmp))[0]

    def test_consistent_tree_is_green(self):
        self.assertEqual([], self.run_check(SOURCES, GOOD_LIST))

    def test_unlisted_image_is_red(self):
        planted = dict(SOURCES)
        planted["mod/a/src/test/java/U.java"] = TEST_SRC.replace("postgres:16", "redis:7")
        errors = self.run_check(planted, GOOD_LIST)
        self.assertTrue(any(e.startswith("UNLISTED image redis:7") for e in errors), errors)

    def test_constant_and_parse_shapes_are_seen(self):
        planted = dict(SOURCES)
        planted["mod/a/src/test/java/V.java"] = ('import org.testcontainers.utility.DockerImageName;\n'
                                                 'class V { static final String X_IMAGE = "nginx:1"; var n = DockerImageName.parse("redis:7"); }\n')
        errors = self.run_check(planted, GOOD_LIST)
        self.assertEqual(2, len([e for e in errors if e.startswith("UNLISTED")]), errors)

    def test_stale_entry_is_red(self):
        errors = self.run_check(SOURCES, GOOD_LIST + "mysql:8.0 mod/a\n")
        self.assertTrue(any(e.startswith("STALE entry mysql:8.0") for e in errors), errors)

    def test_module_drift_is_red(self):
        errors = self.run_check(SOURCES, "testcontainers/ryuk:0.12.0 *\npostgres:16 mod/a,mod/b\n")
        self.assertTrue(any(e.startswith("MODULES differ for postgres:16") for e in errors), errors)

    def test_pulled_module_without_a_group_is_red(self):
        moved = {"mod/z/src/test/java/T.java": TEST_SRC}
        errors = self.run_check(moved, "testcontainers/ryuk:0.12.0 *\npostgres:16 mod/z\n")
        self.assertTrue(any(e.startswith("NO TEST GROUP owns mod/z") for e in errors), errors)

    def test_excluded_module_needs_no_group(self):
        moved = {"mod/z/src/test/java/T.java": TEST_SRC}
        self.assertEqual([], self.run_check(moved, "testcontainers/ryuk:0.12.0 *\npostgres:16 mod/z!\n"))

    def test_missing_ryuk_is_red(self):
        errors = self.run_check(SOURCES, "postgres:16 mod/a\n")
        self.assertTrue(any("testcontainers/ryuk" in e for e in errors), errors)

    def test_two_testcontainers_versions_are_red(self):
        with tempfile.TemporaryDirectory() as tmp:
            tree(Path(tmp), SOURCES, GOOD_LIST)
            (Path(tmp) / "other").mkdir()
            (Path(tmp) / "other/pom.xml").write_text(POM.replace("1.21.4", "1.22.0"))
            errors = check.problems(Path(tmp))[0]
        self.assertTrue(any(e.startswith("Testcontainers version is not a single value") for e in errors), errors)

    def test_with_image_literal_is_seen(self):
        planted = dict(SOURCES)
        planted["mod/a/src/test/java/W.java"] = 'import org.testcontainers.containers.GenericContainer;\nclass W { Object c = x.withImage("redis:7"); }\n'
        errors = self.run_check(planted, GOOD_LIST)
        self.assertTrue(any(e.startswith("UNLISTED image redis:7") for e in errors), errors)

    def test_method_return_image_is_seen(self):
        """#3646: the redis contracts name their image as `String image() { return "redis:7"; }` in a file with no Testcontainers import."""
        planted = dict(SOURCES)
        planted["mod/a/src/test/java/R7.java"] = 'class R7 extends Base {\n    @Override\n    String image() {\n        return "redis:7";\n    }\n}\n'
        errors = self.run_check(planted, GOOD_LIST)
        self.assertTrue(any(e.startswith("UNLISTED image redis:7 (started in mod/a)") for e in errors), errors)

    def test_method_return_shape_needs_a_container_module_and_an_image_name(self):
        """The shape is scoped: a module that starts no container, or a method not named *image*, names no image."""
        other = 'class R7 {\n    String image() {\n        return "redis:7";\n    }\n    String name() {\n        return "x:1";\n    }\n}\n'
        self.assertEqual([], self.run_check({"mod/a/src/test/java/T.java": TEST_SRC, "mod/c/src/test/java/R7.java": other.replace("image()", "x()")}, GOOD_LIST))
        self.assertEqual([], self.run_check({"mod/a/src/test/java/T.java": TEST_SRC, "mod/c/src/main/java/R7.java": other}, GOOD_LIST))

    def test_quarantined_container_test_is_red(self):
        planted = dict(SOURCES)
        planted["mod/a/src/test/java/Q.java"] = '@Tag("quarantine")\n' + TEST_SRC
        errors = self.run_check(planted, GOOD_LIST)
        self.assertTrue(any(e.startswith("QUARANTINED container test mod/a/src/test/java/Q.java") for e in errors), errors)

    def test_the_redis_contract_images_are_in_the_table(self):
        """#3646: the table names the images the redis contract tests return, for the module that starts them."""
        entries = check.listed(check.DEFAULT_ROOT)
        self.assertEqual({"integrations/net/redis"}, entries.get("redis:7"))
        self.assertEqual({"integrations/net/redis"}, entries.get("valkey/valkey:8"))
        self.assertEqual({"integrations/net/redis"}, check.referenced(check.DEFAULT_ROOT).get("redis:7"))

    def test_the_real_tree_is_consistent(self):
        errors = check.problems(check.DEFAULT_ROOT)[0]
        self.assertEqual([], errors)


class MainExitCodeTest(unittest.TestCase):
    """CI's static-gates step reads the exit code of the script itself, so pin it through main(), not problems()."""

    def exit_code(self, sources, listing):
        with tempfile.TemporaryDirectory() as tmp:
            tree(Path(tmp), sources, listing)
            result = subprocess.run(["python3", "-I", "-B", str(TOOLS / "check-ci-test-images.py"), "--root", tmp],
                                    capture_output=True, text=True)
            return result.returncode

    def test_consistent_tree_exits_zero(self):
        self.assertEqual(0, self.exit_code(SOURCES, GOOD_LIST))

    def test_inventory_gap_exits_one(self):
        planted = dict(SOURCES)
        planted["mod/a/src/test/java/U.java"] = TEST_SRC.replace("postgres:16", "redis:7")
        self.assertEqual(1, self.exit_code(planted, GOOD_LIST))

    def test_unreadable_list_exits_two(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = subprocess.run(["python3", "-I", "-B", str(TOOLS / "check-ci-test-images.py"), "--root", tmp],
                                    capture_output=True, text=True)
        self.assertEqual(2, result.returncode)


class StartedImagesTest(unittest.TestCase):
    """#3646: the claim is what a test group STARTED, recorded by the daemon, compared with the list; the source shapes are a pre-check."""
    LISTING = "testcontainers/ryuk:0.12.0 *\npostgres:16 mod/a,mod/b\nredis:7 mod/r\nmysql:8.0 mod/x!\n"
    PLAN = {"mod/a", "mod/r"}

    def problems(self, recorded, plan=None, prefix="mirror.gcr.io/"):
        with tempfile.TemporaryDirectory() as tmp:
            tree(Path(tmp), SOURCES, self.LISTING)
            return check.started_problems(Path(tmp), check.started_images(recorded, prefix), self.PLAN if plan is None else plan)

    def test_listed_images_started_through_the_mirror_are_green(self):
        self.assertEqual([], self.problems("mirror.gcr.io/testcontainers/ryuk:0.12.0\nmirror.gcr.io/postgres:16\nmirror.gcr.io/redis:7\n"))

    def test_a_started_image_missing_from_the_list_fails_by_name(self):
        errors = self.problems("mirror.gcr.io/testcontainers/ryuk:0.12.0\nmirror.gcr.io/valkey/valkey:8\n")
        self.assertEqual(["STARTED image valkey/valkey:8 is not in tools/ci-test-images.txt: add it with the module that starts it"], errors)

    def test_an_image_listed_only_for_other_modules_fails(self):
        errors = self.problems("mirror.gcr.io/testcontainers/ryuk:0.12.0\nmirror.gcr.io/redis:7\n", plan={"mod/a"})
        self.assertTrue(any(e.startswith("STARTED image redis:7 is listed for ['mod/r']") for e in errors), errors)

    def test_an_excluded_module_row_still_names_the_image(self):
        self.assertEqual([], self.problems("mirror.gcr.io/testcontainers/ryuk:0.12.0\nmirror.gcr.io/mysql:8.0\n", plan={"mod/x"}))

    def test_a_non_hub_image_is_compared_as_named(self):
        self.assertEqual(["STARTED image cgr.dev/x/y:1 is not in tools/ci-test-images.txt: add it with the module that starts it"],
                         self.problems("mirror.gcr.io/testcontainers/ryuk:0.12.0\ncgr.dev/x/y:1\n"))

    def test_an_empty_record_for_a_group_that_starts_containers_is_red(self):
        errors = self.problems("")
        self.assertTrue(any(e.startswith("NOTHING RECORDED") for e in errors), errors)

    def test_an_empty_record_for_a_group_with_no_listed_module_is_green(self):
        self.assertEqual([], self.problems("", plan={"mod/none"}))

    def exit_code(self, recorded, plan="mod/a\n"):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tree(root, SOURCES, self.LISTING)
            (root / "events").write_text(recorded)
            (root / "plan").write_text(plan)
            return subprocess.run(["python3", "-I", "-B", str(TOOLS / "check-ci-test-images.py"), "--root", tmp, "--started", str(root / "events"),
                                   "--plan", str(root / "plan"), "--prefix", "mirror.gcr.io/"], capture_output=True, text=True).returncode

    def test_the_script_exits_one_on_an_unlisted_started_image_and_zero_otherwise(self):
        self.assertEqual(0, self.exit_code("mirror.gcr.io/testcontainers/ryuk:0.12.0\nmirror.gcr.io/postgres:16\n"))
        self.assertEqual(1, self.exit_code("mirror.gcr.io/testcontainers/ryuk:0.12.0\nmirror.gcr.io/valkey/valkey:8\n"))

    def test_the_script_exits_two_when_the_record_is_unreadable(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = subprocess.run(["python3", "-I", "-B", str(TOOLS / "check-ci-test-images.py"), "--started", f"{tmp}/none", "--plan", f"{tmp}/none"],
                                    capture_output=True, text=True)
        self.assertEqual(2, result.returncode)


class WorkflowWiringTest(unittest.TestCase):
    """#3646: the recorder brackets the test step and the comparison runs even when the tests fail; a missing step makes the claim vacuous."""
    def test_the_test_group_job_records_then_tests_then_compares_always(self):
        text = (TOOLS.parent / ".github/workflows/ci.yml").read_text()
        record = text.index("- name: Record the images the tests start")
        tests = text.index("- name: Test this group's modules")
        compare = text.index("- name: Compare the started images with the inventory")
        self.assertLess(record, tests)
        self.assertLess(tests, compare)
        step = text[compare:text.index("      - name:", compare + 1)]
        self.assertIn("always() && steps.record.outcome == 'success'", step)
        self.assertIn("ci-record-started-images.sh stop started-images.txt", step)
        self.assertIn('check-ci-test-images.py --started started-images.txt --plan "test-group-${{ matrix.group }}.txt"', step)


class RecorderTest(unittest.TestCase):
    """tools/ci-record-started-images.sh against a fake `docker events` that prints the given images and then stays alive."""
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        fake = self.tmp / "docker"
        fake.write_text('#!/usr/bin/env bash\n[ "$1" = events ] || exit 9\n[ -n "${FAKE_DIE:-}" ] && exit 3\n'
                        'printf "%s\\n" ${FAKE_IMAGES:-}\nexec sleep 30\n')
        fake.chmod(fake.stat().st_mode | stat.S_IXUSR)
        self.out = self.tmp / "started.txt"

    def run_script(self, mode, **env):
        full = dict(os.environ, CI_RECORD_DOCKER=str(self.tmp / "docker"), CI_RECORD_SETTLE="1")
        full.update(env)
        return subprocess.run([str(TOOLS / "ci-record-started-images.sh"), mode, str(self.out)], env=full, capture_output=True, text=True)

    def test_records_the_images_and_stops_cleanly(self):
        self.assertEqual(0, self.run_script("start", FAKE_IMAGES="mirror.gcr.io/redis:7 mirror.gcr.io/postgres:16").returncode)
        stopped = self.run_script("stop")
        self.assertEqual(0, stopped.returncode, stopped.stdout)
        self.assertEqual(["mirror.gcr.io/redis:7", "mirror.gcr.io/postgres:16"], self.out.read_text().splitlines())
        self.assertIn("2 distinct image(s)", stopped.stdout)

    def test_a_recorder_that_died_at_start_fails_the_step(self):
        result = self.run_script("start", FAKE_DIE="1")
        self.assertEqual(1, result.returncode)
        self.assertIn("exited at start", result.stdout)

    def test_a_recorder_that_died_during_the_run_fails_the_stop(self):
        self.assertEqual(0, self.run_script("start").returncode)
        os.kill(int((self.tmp / "started.txt.pid").read_text()), 9)
        self.assertEqual(1, self.run_script("stop").returncode)

    def test_stop_without_start_fails(self):
        self.assertEqual(1, self.run_script("stop").returncode)


class PrepullTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        fake = self.tmp / "docker"
        fake.write_text('#!/bin/bash\necho "$@" >> "$FAKE_LOG"\n'
                        '[ "${FAKE_FAIL:-}" = all ] && exit 1\n'
                        'if [ -n "${FAKE_FAIL_ONCE:-}" ] && [ ! -e "$FAKE_FAIL_ONCE" ]; then touch "$FAKE_FAIL_ONCE"; exit 1; fi\nexit 0\n')
        fake.chmod(fake.stat().st_mode | stat.S_IEXEC)
        (self.tmp / "list").write_text("testcontainers/ryuk:0.12.0 *\npostgres:16 mod/a\nmysql:8.0 mod/b\nx.io/y:1 mod/c!\n")
        (self.tmp / "plan").write_text("mod/a\n")
        nap = self.tmp / "sleep"
        nap.write_text('#!/bin/bash\necho "$@" >> "$FAKE_SLEEP_LOG"\n')
        nap.chmod(nap.stat().st_mode | stat.S_IEXEC)
        self.log = self.tmp / "log"
        self.naps = self.tmp / "naps"

    def run_with(self, listing, plan, **env):
        (self.tmp / "list").write_text(listing)
        (self.tmp / "plan").write_text(plan)
        return self.run_script(**env)

    def run_script(self, **env):
        full = dict(os.environ, CI_TEST_IMAGES_LIST=str(self.tmp / "list"), CI_PREPULL_DOCKER=str(self.tmp / "docker"),
                    CI_PREPULL_SLEEP=str(self.tmp / "sleep"), FAKE_LOG=str(self.log), FAKE_SLEEP_LOG=str(self.naps), TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX="mirror.gcr.io/")
        full.setdefault("CI_PREPULL_BACKOFF_BASE", "0")
        full.update(env)
        return subprocess.run([str(TOOLS / "ci-prepull-images.sh"), str(self.tmp / "plan")], env=full, capture_output=True, text=True)

    def test_pulls_exactly_the_planned_images_through_the_mirror(self):
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual(["pull mirror.gcr.io/testcontainers/ryuk:0.12.0", "pull mirror.gcr.io/postgres:16"],
                         self.log.read_text().splitlines())

    def test_a_transient_failure_is_retried(self):
        result = self.run_script(FAKE_FAIL_ONCE=str(self.tmp / "once"))
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual(3, len(self.log.read_text().splitlines()))

    def test_a_persistent_failure_fails_the_step_after_four_attempts(self):
        result = self.run_script(FAKE_FAIL="all")
        self.assertEqual(1, result.returncode)
        self.assertEqual(8, len(self.log.read_text().splitlines()))
        self.assertIn("could not pull mirror.gcr.io/postgres:16 after 4 attempts", result.stdout)

    def test_backoff_doubles_between_attempts_and_a_removed_retry_is_red(self):
        result = self.run_script(FAKE_FAIL="all", CI_PREPULL_BACKOFF_BASE="10")
        self.assertEqual(1, result.returncode)
        self.assertEqual(["10", "20", "40"] * 2, self.naps.read_text().splitlines())

    def test_pull_failing_n_times_retries_then_fails_nonzero(self):
        result = self.run_script(FAKE_FAIL="all", CI_PREPULL_ATTEMPTS="2")
        self.assertEqual(1, result.returncode)
        self.assertEqual(4, len(self.log.read_text().splitlines()))
        self.assertEqual(2, len(self.naps.read_text().splitlines()))

    def test_non_hub_registries_are_pulled_as_named(self):
        listing = "x.io/y:1 mod/a\nhost:5000/z:2 mod/a\nlocalhost/w:3 mod/a\nlib/v:4 mod/a\n"
        self.assertEqual(0, self.run_with(listing, "mod/a\n").returncode)
        self.assertEqual(["pull x.io/y:1", "pull host:5000/z:2", "pull localhost/w:3", "pull mirror.gcr.io/lib/v:4"],
                         self.log.read_text().splitlines())

    def test_excluded_module_is_never_pulled_even_when_planned(self):
        result = self.run_with("testcontainers/ryuk:0.12.0 *\nx.io/y:1 mod/c!\n", "mod/c\n")
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual(["pull mirror.gcr.io/testcontainers/ryuk:0.12.0"], self.log.read_text().splitlines())

    def test_without_a_plan_every_image_but_the_excluded_is_pulled(self):
        (self.tmp / "list").write_text("testcontainers/ryuk:0.12.0 *\npostgres:16 mod/a\nx.io/y:1 mod/c!\n")
        full = dict(os.environ, CI_TEST_IMAGES_LIST=str(self.tmp / "list"), CI_PREPULL_DOCKER=str(self.tmp / "docker"),
                    CI_PREPULL_SLEEP=str(self.tmp / "sleep"), FAKE_LOG=str(self.log), FAKE_SLEEP_LOG=str(self.naps),
                    TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX="mirror.gcr.io/")
        result = subprocess.run([str(TOOLS / "ci-prepull-images.sh")], env=full, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual(["pull mirror.gcr.io/testcontainers/ryuk:0.12.0", "pull mirror.gcr.io/postgres:16"],
                         self.log.read_text().splitlines())

    def test_empty_selection_fails(self):
        result = self.run_with("postgres:16 mod/a\n", "mod/z\n")
        self.assertEqual(1, result.returncode)
        self.assertIn("no image selected", result.stdout)

    def test_module_names_match_exactly_not_by_prefix(self):
        result = self.run_with("testcontainers/ryuk:0.12.0 *\npostgres:16 mod/a\n", "mod/ab\nxmod/a\n")
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual(["pull mirror.gcr.io/testcontainers/ryuk:0.12.0"], self.log.read_text().splitlines())


if __name__ == "__main__":
    unittest.main()
