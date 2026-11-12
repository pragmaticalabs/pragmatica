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

    def test_the_real_tree_is_consistent(self):
        errors = check.problems(check.DEFAULT_ROOT)[0]
        self.assertEqual([], errors)


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
        self.log = self.tmp / "log"

    def run_script(self, **env):
        full = dict(os.environ, CI_TEST_IMAGES_LIST=str(self.tmp / "list"), CI_PREPULL_DOCKER=str(self.tmp / "docker"),
                    CI_PREPULL_BACKOFF_BASE="0", FAKE_LOG=str(self.log), TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX="mirror.gcr.io/", **env)
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


if __name__ == "__main__":
    unittest.main()
