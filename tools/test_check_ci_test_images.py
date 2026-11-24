"""Pins for tools/check-ci-test-images.py and tools/ci-prepull-images.sh (#3124)."""
import importlib.util
import os
import re
import shlex
import shutil
import stat
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
REPO = TOOLS.parent
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

    def test_library_default_tiny_image_needs_no_source(self):
        """#3646: Testcontainers' own alpine probe image is started by the library, so no source names it and it is not STALE."""
        self.assertEqual([], self.run_check(SOURCES, GOOD_LIST + "alpine:3.17 mod/a\n"))

    def test_two_library_default_tiny_images_are_red(self):
        errors = self.run_check(SOURCES, GOOD_LIST + "alpine:3.17 mod/a\nalpine:3.18 mod/a\n")
        self.assertTrue(any(e.startswith("expected at most one library-default alpine:") for e in errors), errors)

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

    def test_method_return_shape_is_scoped_to_test_sources_of_container_modules(self):
        """#3646 scope: a module without Testcontainers, and main sources even of a container module, name no started image."""
        method = 'class R7 {\n    String image() {\n        return "redis:7";\n    }\n}\n'
        self.assertEqual([], self.run_check({"mod/a/src/test/java/T.java": TEST_SRC, "mod/c/src/test/java/R7.java": method}, GOOD_LIST))
        self.assertEqual([], self.run_check({"mod/a/src/test/java/T.java": TEST_SRC, "mod/a/src/main/java/R7.java": method}, GOOD_LIST))

    def test_method_return_shape_reads_a_capitalised_image_name(self):
        planted = {"mod/a/src/test/java/T.java": TEST_SRC, "mod/a/src/test/java/R7.java": 'class R7 {\n    String getImage() {\n        return "redis:7";\n    }\n}\n'}
        errors = self.run_check(planted, GOOD_LIST)
        self.assertTrue(any(e.startswith("UNLISTED image redis:7") for e in errors), errors)

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
    RYUK = "mirror.gcr.io/testcontainers/ryuk:0.12.0\n"

    def problems(self, recorded, plan=None, prefix="mirror.gcr.io/", listing=None):
        with tempfile.TemporaryDirectory() as tmp:
            tree(Path(tmp), SOURCES, self.LISTING if listing is None else listing)
            return check.started_problems(Path(tmp), check.started_images(recorded, prefix), self.PLAN if plan is None else plan)

    def test_listed_images_started_through_the_mirror_are_green(self):
        self.assertEqual([], self.problems(self.RYUK + "mirror.gcr.io/postgres:16\nmirror.gcr.io/redis:7\n"))

    def test_a_started_image_missing_from_the_list_fails_by_name(self):
        errors = self.problems(self.RYUK + "mirror.gcr.io/valkey/valkey:8\n")
        self.assertEqual(["STARTED image valkey/valkey:8 is not in tools/ci-test-images.txt: add it with the module that starts it"], errors)

    def test_an_image_listed_only_for_other_modules_fails(self):
        errors = self.problems(self.RYUK + "mirror.gcr.io/redis:7\n", plan={"mod/a"})
        self.assertTrue(any(e.startswith("STARTED image redis:7 is listed for ['mod/r']") for e in errors), errors)

    def test_an_excluded_row_is_not_a_pre_pull_row_so_starting_it_fails_by_name(self):
        errors = self.problems(self.RYUK + "mirror.gcr.io/mysql:8.0\n", plan={"mod/x"})
        self.assertEqual(1, len(errors), errors)
        self.assertTrue(errors[0].startswith("STARTED image mysql:8.0 is listed only as excluded (`!`) for ['mod/x!']"), errors)

    def test_an_excluded_image_started_next_to_a_listed_module_still_fails(self):
        errors = self.problems(self.RYUK + "mirror.gcr.io/postgres:16\nmirror.gcr.io/mysql:8.0\n", plan={"mod/a", "mod/x"})
        self.assertEqual(1, len(errors), errors)
        self.assertIn("mysql:8.0", errors[0])

    def test_a_listed_row_wins_over_an_excluded_row_of_the_same_image_in_the_plan(self):
        listing = "testcontainers/ryuk:0.12.0 *\nmysql:8.0 mod/a,mod/x!\n"
        self.assertEqual([], self.problems(self.RYUK + "mirror.gcr.io/mysql:8.0\n", plan={"mod/a", "mod/x"}, listing=listing))

    def test_a_non_hub_image_is_compared_as_named(self):
        self.assertEqual(["STARTED image cgr.dev/x/y:1 is not in tools/ci-test-images.txt: add it with the module that starts it"],
                         self.problems(self.RYUK + "cgr.dev/x/y:1\n"))

    def test_an_empty_record_for_a_group_that_pulls_a_listed_image_is_red(self):
        errors = self.problems("")
        self.assertTrue(any(e.startswith("NOTHING RECORDED") for e in errors), errors)

    def test_an_empty_record_for_a_group_with_no_listed_module_is_green(self):
        self.assertEqual([], self.problems("", plan={"mod/none"}))

    G1_LISTING = "testcontainers/ryuk:0.12.0 *\ncgr.dev/chainguard/minio@sha256:0f mod/g1!\n"

    def test_g1_a_group_whose_modules_start_nothing_and_list_only_excluded_rows_is_green_with_an_empty_record(self):
        self.assertEqual([], self.problems("", plan={"mod/g1"}, listing=self.G1_LISTING))

    def test_g1_starting_its_excluded_image_is_red_by_name(self):
        errors = self.problems(self.RYUK + "cgr.dev/chainguard/minio@sha256:0f\n", plan={"mod/g1"}, listing=self.G1_LISTING)
        self.assertEqual(1, len(errors), errors)
        self.assertTrue(errors[0].startswith("STARTED image cgr.dev/chainguard/minio@sha256:0f is listed only as excluded"), errors)

    def test_the_verdict_table_names_every_state_for_started_and_not_started(self):
        states = {check.LISTED, check.EXCLUDED, check.ELSEWHERE, check.BY_ID, check.ABSENT}
        self.assertEqual({(s, started) for s in states for started in (True, False)}, set(check.VERDICT))
        for state in states:
            self.assertEqual(state == check.LISTED, check.VERDICT[(state, True)] is None, state)

    def test_row_state_follows_the_modules_the_pre_pull_selects(self):
        plan = {"a", "b"}
        self.assertEqual(check.LISTED, check.row_state({"*"}, plan))
        self.assertEqual(check.LISTED, check.row_state({"a"}, plan))
        self.assertEqual(check.LISTED, check.row_state({"a!", "b"}, plan))
        self.assertEqual(check.EXCLUDED, check.row_state({"a!", "z"}, plan))
        self.assertEqual(check.ELSEWHERE, check.row_state({"z", "y!"}, plan))
        self.assertEqual(check.ABSENT, check.row_state(None, plan))

    def exit_code(self, recorded, plan="mod/a\n", listing=None):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tree(root, SOURCES, self.LISTING if listing is None else listing)
            (root / "events").write_text(recorded)
            (root / "plan").write_text(plan)
            result = subprocess.run(["python3", "-I", "-B", str(TOOLS / "check-ci-test-images.py"), "--root", tmp, "--started", str(root / "events"),
                                     "--plan", str(root / "plan"), "--prefix", "mirror.gcr.io/"], capture_output=True, text=True)
            return result.returncode, result.stdout

    def test_the_script_exits_one_on_an_unlisted_started_image_and_zero_otherwise(self):
        self.assertEqual(0, self.exit_code(self.RYUK + "mirror.gcr.io/postgres:16\n")[0])
        self.assertEqual(1, self.exit_code(self.RYUK + "mirror.gcr.io/valkey/valkey:8\n")[0])

    def test_a_plan_with_padding_and_blank_lines_names_its_modules(self):
        self.assertEqual(0, self.exit_code(self.RYUK + "mirror.gcr.io/redis:7\n", plan="\n  mod/r  \n\n")[0])

    def test_the_script_says_nothing_expected_for_g1_and_exits_zero(self):
        code, out = self.exit_code("", plan="mod/g1\n", listing=self.G1_LISTING)
        self.assertEqual(0, code, out)
        self.assertIn("nothing expected", out)

    def test_the_script_exits_two_when_the_record_is_unreadable(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = subprocess.run(["python3", "-I", "-B", str(TOOLS / "check-ci-test-images.py"), "--started", f"{tmp}/none", "--plan", f"{tmp}/none"],
                                    capture_output=True, text=True)
        self.assertEqual(2, result.returncode)

    def test_started_and_plan_go_together(self):
        for argv in (["--started", "x"], ["--plan", "x"]):
            result = subprocess.run(["python3", "-I", "-B", str(TOOLS / "check-ci-test-images.py")] + argv, capture_output=True, text=True)
            self.assertEqual(2, result.returncode, argv)
            self.assertIn("go together", result.stderr)

    def test_a_blank_line_or_padding_in_the_record_is_not_an_image(self):
        self.assertEqual({"redis:7"}, check.started_images("\n  mirror.gcr.io/redis:7  \n\n   \n", "mirror.gcr.io/"))

    def test_the_same_image_recorded_twice_is_one_image(self):
        self.assertEqual({"redis:7"}, check.started_images("redis:7\nredis:7\n", ""))


class NormalisationTest(unittest.TestCase):
    """#3646 F3: digests, registry prefixes and Docker Hub spellings are compared by canonical() alone."""
    FORMS = {
        "redis:7": "redis:7",
        "mirror.gcr.io/redis:7": "redis:7",
        "docker.io/library/redis:7": "redis:7",
        "docker.io/redis:7": "redis:7",
        "index.docker.io/library/redis:7": "redis:7",
        "registry-1.docker.io/library/redis:7": "redis:7",
        "library/redis:7": "redis:7",
        "mirror.gcr.io/library/redis:7": "redis:7",
        "redis": "redis:latest",
        "mirror.gcr.io/valkey/valkey:8": "valkey/valkey:8",
        "docker.io/valkey/valkey:8": "valkey/valkey:8",
        "redis:7@sha256:aa": "redis:7@sha256:aa",
        "cgr.dev/chainguard/minio@sha256:aa": "cgr.dev/chainguard/minio@sha256:aa",
        "localhost:5000/redis": "localhost:5000/redis:latest",
        "  redis:7  ": "redis:7",
    }

    def test_every_spelling_has_one_canonical_form(self):
        for form, expected in self.FORMS.items():
            self.assertEqual(expected, check.canonical(form, "mirror.gcr.io/"), form)

    def test_an_image_id_is_left_alone(self):
        for image in ("0123456789ab", "sha256:" + "ab" * 32, "ab" * 32):
            self.assertEqual(image, check.canonical(image, ""))

    def started(self, recorded, listing="testcontainers/ryuk:0.12.0 *\nredis:7 mod/r\ncgr.dev/c/minio@sha256:aa mod/r\n"):
        with tempfile.TemporaryDirectory() as tmp:
            tree(Path(tmp), SOURCES, listing)
            return check.started_problems(Path(tmp), check.started_images(recorded, "mirror.gcr.io/"), {"mod/r"})

    def test_every_spelling_of_a_listed_image_is_green(self):
        for form in ("redis:7", "mirror.gcr.io/redis:7", "docker.io/library/redis:7", "library/redis:7", "redis:7@sha256:bb"):
            self.assertEqual([], self.started(form + "\n"), form)

    def test_a_digest_other_than_the_pinned_one_is_red(self):
        errors = self.started("cgr.dev/c/minio@sha256:bb\n")
        self.assertTrue(any(e.startswith("STARTED image cgr.dev/c/minio@sha256:bb is not in") for e in errors), errors)
        self.assertEqual([], self.started("cgr.dev/c/minio@sha256:aa\n"))

    def test_a_bare_name_is_latest_and_so_is_not_redis_7(self):
        errors = self.started("redis\n")
        self.assertTrue(any(e.startswith("STARTED image redis:latest is not in") for e in errors), errors)

    def test_an_image_id_is_red_with_its_own_reason(self):
        errors = self.started("0123456789ab\n")
        self.assertTrue(any(e.startswith("STARTED a container from image ID 0123456789ab") for e in errors), errors)

    def test_every_row_of_the_real_list_matches_its_own_recorded_forms_and_an_excluded_row_is_red(self):
        entries = check.listed(REPO)
        for image, modules in entries.items():
            hub = check.canonical(image) == image and "." not in image.split("/")[0]
            forms = [image] + (["mirror.gcr.io/" + image, "docker.io/" + image] if hub else [])
            pulled_for = {m for m in modules if m != "*" and not m.endswith("!")}
            excluded_for = {m[:-1] for m in modules if m.endswith("!")}
            for form in forms:
                recorded = check.started_images(form, "mirror.gcr.io/")
                if "*" in modules or pulled_for:
                    self.assertEqual([], check.started_problems(REPO, recorded, pulled_for or {"any"}), form)
                else:
                    errors = check.started_problems(REPO, recorded, excluded_for)
                    self.assertTrue(any("listed only as excluded" in e for e in errors), (form, errors))


    # What test-group-g3 recorded in CI run 45352487466 (the mirror prefix is stripped by started_images): the group that first
    # showed Testcontainers' own alpine probe image (#3646); the real list must cover it for g3's modules and no other group's.
    G3_STARTED = "alpine:3.17\nlocalstack/localstack:3.8\npostgres:16\npostgres:16-alpine\nredis:7\ntestcontainers/ryuk:0.12.0\nvalkey/valkey:8\n"

    def g3_plan(self):
        return {line.split()[1] for line in (REPO / check.GROUPS).read_text().splitlines()
                if line.startswith("g3 ") and "#" not in line.split()[1]}

    def test_the_real_list_covers_what_group_g3_started(self):
        recorded = check.started_images(self.G3_STARTED, "mirror.gcr.io/")
        self.assertEqual([], check.started_problems(REPO, recorded, self.g3_plan()))

    def test_dropping_the_tiny_image_row_names_alpine_for_group_g3(self):
        with tempfile.TemporaryDirectory() as tmp:
            shutil.copytree(REPO / "tools", Path(tmp) / "tools", ignore=shutil.ignore_patterns("__pycache__"))
            listing = Path(tmp) / check.LIST
            listing.write_text("".join(l for l in listing.read_text().splitlines(True) if not l.startswith("alpine:3.17 ")))
            errors = check.started_problems(Path(tmp), check.started_images(self.G3_STARTED, "mirror.gcr.io/"), self.g3_plan())
        self.assertEqual(["STARTED image alpine:3.17 is not in tools/ci-test-images.txt: add it with the module that starts it"], errors)


WORKFLOW = REPO / ".github/workflows/ci.yml"


def test_group_jobs(text):
    """{job: {"env": {K: V}, "steps": [{key: scalar-or-block}]}} for the jobs named test-group*; a reader for this file's block shape
    (2-space job keys, `env:` at 4, steps `- ` at 6 with keys at 8, block scalars deeper), so the gate needs no YAML library."""
    jobs, job, section, step, key = {}, None, None, None, None
    in_jobs = False
    for line in text.splitlines():
        if re.match(r"^\S", line):
            in_jobs = line.startswith("jobs:")
            job = None
            continue
        if not in_jobs or not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        if indent == 2 and line.rstrip().endswith(":"):
            name = line.strip()[:-1]
            job = jobs.setdefault(name, {"env": {}, "steps": []}) if name.startswith("test-group") else None
            section = step = key = None
        elif job is None:
            continue
        elif indent == 4:
            section = line.strip()[:-1] if line.strip().endswith(":") else None
        elif section == "env" and indent == 6 and ":" in line:
            k, v = line.strip().split(":", 1)
            job["env"][k] = v.strip()
        elif section == "steps" and indent == 6 and line.lstrip().startswith("- "):
            step = {}
            job["steps"].append(step)
            k, v = line.lstrip()[2:].split(":", 1)
            step[k.strip()], key = v.strip(), k.strip()
        elif section == "steps" and step is not None and indent == 8 and ":" in line:
            k, v = line.strip().split(":", 1)
            step[k.strip()], key = v.strip(), k.strip()
        elif section == "steps" and step is not None and key is not None and indent > 8:
            step[key] = step[key] + "\n" + line.strip() if step[key] not in ("|", ">") else line.strip()
    return jobs


def wiring_errors(text):
    """Why the test-group jobs of a ci.yml text do not bracket their tests with the recorder and the comparison (empty: wired)."""
    jobs = test_group_jobs(text)
    if not jobs:
        return ["no test-group job found: the wiring test examined nothing"]
    errors = []
    for name, job in jobs.items():
        steps = job["steps"]

        def find(fragment):
            return next((i for i, s in enumerate(steps) if fragment in s.get("run", "")), None)

        prepull, record, compare = find("ci-prepull-images.sh"), find("ci-record-started-images.sh start"), find("check-ci-test-images.py --started")
        if None in (prepull, record, compare):
            errors.append(f"{name}: missing step (pre-pull {prepull}, record {record}, compare {compare})")
            continue
        ids = steps[record].get("id", "")
        if not ids:
            errors.append(f"{name}: the record step has no id, so the compare step's `steps.<id>.outcome` is never true and it is silently skipped")
        condition = steps[compare].get("if", "")
        if "always()" not in condition or f"steps.{ids}.outcome == 'success'" not in condition or not ids:
            errors.append(f"{name}: the compare step's `if` must be always() and steps.{ids or '<id>'}.outcome == 'success', got: {condition!r}")
        if not any("mvn" in s.get("run", "") for s in steps[record + 1:compare]):
            errors.append(f"{name}: no mvn step between the record step and the compare step")
        if not prepull < record:
            errors.append(f"{name}: the record step must come after the pre-pull (pulls create no containers, but the order is the claim)")
        for i in (record, compare):
            if not steps[i].get("timeout-minutes"):
                errors.append(f"{name}: no timeout-minutes on {steps[i].get('name')}: a hung recorder or comparison would hold the job")
            if steps[i].get("continue-on-error"):
                errors.append(f"{name}: continue-on-error on {steps[i].get('name')}")
        group = "g1"
        expand = lambda s: (s.replace("${{ matrix.group }}", group).replace("${TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX}", job["env"].get("TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX", ""))
                             .replace("$TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX", job["env"].get("TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX", "")))
        commands = [shlex.split(expand(c)) for c in steps[compare]["run"].replace("\\\n", " ").splitlines() if c.strip()]
        started_file = shlex.split(steps[record]["run"])[-1]
        stop = next((c for c in commands if "ci-record-started-images.sh" in c[0]), None)
        if stop is None or stop[1:] != ["stop", started_file]:
            errors.append(f"{name}: the compare step must run `ci-record-started-images.sh stop {started_file}`, got {stop}")
        check_argv = next((c for c in commands if any(a.endswith("check-ci-test-images.py") for a in c)), None)
        if check_argv is None:
            errors.append(f"{name}: the compare step does not run check-ci-test-images.py")
            continue
        try:
            args = check.build_parser().parse_args(check_argv[[a.endswith("check-ci-test-images.py") for a in check_argv].index(True) + 1:])
        except SystemExit:
            errors.append(f"{name}: the checker cannot parse the compare step's argv {check_argv}")
            continue
        plan = shlex.split(expand(steps[prepull]["run"]))[-1]
        env_prefix = job["env"].get("TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX", "")
        if str(args.started) != started_file:
            errors.append(f"{name}: --started is {args.started}, the recorder writes {started_file}")
        if str(args.plan) != plan:
            errors.append(f"{name}: --plan is {args.plan}, the pre-pull reads {plan}")
        if not env_prefix or args.prefix != env_prefix:
            errors.append(f"{name}: --prefix is {args.prefix!r}, the job's TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX is {env_prefix!r}: "
                          f"recorded images would not be normalised the way Testcontainers prefixed them")
    return errors


class WorkflowWiringTest(unittest.TestCase):
    """#3646: the recorder brackets the test step and the comparison runs even when the tests fail; a missing id, `if` or argument makes
    the claim vacuous, so the ci.yml wiring is parsed and checked against what check-ci-test-images.py parses, never trusted."""
    TEXT = WORKFLOW.read_text()

    def test_every_test_group_job_is_wired(self):
        jobs = test_group_jobs(self.TEXT)
        self.assertEqual(["test-group"], sorted(jobs), "the reader found the test-group job (a vacuous pass is the failure mode)")
        self.assertEqual([], wiring_errors(self.TEXT))

    def mutated(self, old, new):
        self.assertEqual(1, self.TEXT.count(old), old)
        return wiring_errors(self.TEXT.replace(old, new))

    def test_a_removed_record_id_is_red(self):
        errors = self.mutated("        id: record\n", "")
        self.assertTrue(any("no id" in e for e in errors), errors)

    def test_a_renamed_record_id_is_red(self):
        errors = self.mutated("        id: record\n", "        id: recorder\n")
        self.assertTrue(any("`if` must be" in e for e in errors), errors)

    def test_a_dropped_prefix_is_red(self):
        errors = self.mutated(' \\\n            --prefix "$TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX"', "")
        self.assertTrue(any("--prefix" in e for e in errors), errors)

    def test_a_wrong_plan_file_is_red(self):
        errors = self.mutated('--plan "test-group-${{ matrix.group }}.txt"', '--plan "plan.txt"')
        self.assertTrue(any("--plan" in e for e in errors), errors)

    def test_a_compare_step_without_always_is_red(self):
        errors = self.mutated("if: ${{ always() && steps.record.outcome == 'success' }}", "if: ${{ steps.record.outcome == 'success' }}")
        self.assertTrue(any("`if` must be" in e for e in errors), errors)

    def test_a_compare_step_that_does_not_stop_the_recorder_is_red(self):
        errors = self.mutated("          tools/ci-record-started-images.sh stop started-images.txt\n", "")
        self.assertTrue(any("stop" in e for e in errors), errors)

    def test_a_compare_step_without_a_timeout_is_red(self):
        errors = self.mutated("        timeout-minutes: 2\n\n      # The control", "\n      # The control")
        self.assertTrue(any("no timeout-minutes" in e for e in errors), errors)

    def test_a_record_step_that_stops_instead_of_starting_is_red(self):
        self.assertNotEqual([], self.mutated("run: tools/ci-record-started-images.sh start started-images.txt", "run: tools/ci-record-started-images.sh stop started-images.txt"))

    def test_a_removed_compare_step_or_record_step_is_red(self):
        self.assertNotEqual([], wiring_errors(self.TEXT.replace("check-ci-test-images.py --started", "true --started")))
        self.assertNotEqual([], wiring_errors(self.TEXT.replace("ci-record-started-images.sh start", "true")))

    def test_the_recorder_after_the_tests_is_red(self):
        record = self.TEXT.index("      - name: Record the images the tests start")
        tests = self.TEXT.index("      # Ember's and Consensus's tests run in their shard jobs")
        moved = self.TEXT[:record] + self.TEXT[tests:self.TEXT.index("      - name: Compare the started images")] + self.TEXT[record:tests] + self.TEXT[self.TEXT.index("      - name: Compare the started images"):]
        self.assertNotEqual([], wiring_errors(moved))

    def test_a_text_without_a_test_group_job_is_red_not_vacuously_green(self):
        self.assertNotEqual([], wiring_errors("jobs:\n  other:\n    steps: []\n"))


class RecorderTest(unittest.TestCase):
    """tools/ci-record-started-images.sh against a fake `docker events` that prints the given images and then stays alive."""
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        fake = self.tmp / "docker"
        fake.write_text('#!/usr/bin/env bash\n[ "$1" = events ] || exit 9\nprintf "%s\\n" "$@" > "${FAKE_ARGV:-/dev/null}"\n'
                        '[ -n "${FAKE_DIE:-}" ] && { echo "fake docker: boom" >&2; exit 3; }\n[ -z "${FAKE_IMAGES:-}" ] || printf "%s\\n" ${FAKE_IMAGES}\nexec sleep 30\n')
        fake.chmod(fake.stat().st_mode | stat.S_IXUSR)
        self.out = self.tmp / "started.txt"

    def run_script(self, mode, **env):
        full = dict(os.environ, CI_RECORD_DOCKER=str(self.tmp / "docker"), CI_RECORD_SETTLE="1", FAKE_ARGV=str(self.tmp / "argv"))
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
        self.assertIn("fake docker: boom", result.stdout)

    def test_a_recorder_that_died_during_the_run_fails_the_stop(self):
        self.assertEqual(0, self.run_script("start").returncode)
        os.kill(int((self.tmp / "started.txt.pid").read_text()), 9)
        self.assertEqual(1, self.run_script("stop").returncode)

    def test_stop_without_start_fails(self):
        self.assertEqual(1, self.run_script("stop").returncode)

    # I3 (#3646): the invocation the recorder makes. RealDockerRecorderTest runs the same script against a real daemon (cloudbb-5, see
    # the PR body) and so proves these flags and this format; this assertion pins the fake to that invocation, so the two cannot drift.
    REAL_EVENTS_ARGV = ["--filter", "type=container", "--filter", "event=create", "--format", "{{.Actor.Attributes.image}}"]

    def test_the_recorder_invokes_docker_events_exactly_as_proved_against_a_real_daemon(self):
        self.assertEqual(0, self.run_script("start").returncode)
        self.run_script("stop")
        argv = (self.tmp / "argv").read_text().splitlines()
        self.assertEqual(["events", "--since"], argv[:2])
        self.assertRegex(argv[2], r"^\d{10}$")
        self.assertLessEqual(abs(int(argv[2]) - time.time()), 30)
        self.assertEqual(self.REAL_EVENTS_ARGV, argv[3:])

    def test_stop_kills_the_recorder(self):
        self.run_script("start")
        pid = int((self.tmp / "started.txt.pid").read_text())
        self.assertEqual(0, self.run_script("stop").returncode)
        deadline = time.time() + 10
        while time.time() < deadline:
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return
            time.sleep(0.1)
        os.kill(pid, 9)
        self.fail("the recorder was still running 10 s after stop")

    def test_start_truncates_a_record_left_by_an_earlier_run(self):
        self.out.write_text("stale/image:1\n")
        self.assertEqual(0, self.run_script("start").returncode)
        self.run_script("stop")
        self.assertEqual("", self.out.read_text())

    def test_the_distinct_count_counts_distinct_images_and_not_blank_lines(self):
        self.run_script("start", FAKE_IMAGES="a:1 a:1 b:2 a:1")
        self.assertIn("2 distinct image(s)", self.run_script("stop").stdout)

    def test_a_missing_file_argument_or_unknown_mode_is_a_usage_error(self):
        full = dict(os.environ, CI_RECORD_DOCKER=str(self.tmp / "docker"))
        self.assertEqual(2, subprocess.run([str(TOOLS / "ci-record-started-images.sh"), "start"], env=full, capture_output=True).returncode)
        self.assertEqual(2, subprocess.run([str(TOOLS / "ci-record-started-images.sh"), "pause", str(self.out)], env=full, capture_output=True).returncode)


@unittest.skipUnless(os.environ.get("RECORDER_REAL_DOCKER") == "1" and shutil.which("docker"),
                     "needs a real Docker daemon: RECORDER_REAL_DOCKER=1 RECORDER_REAL_IMAGE=<a local image> (run on cloudbb-5)")
class RealDockerRecorderTest(unittest.TestCase):
    """I3: the real `docker events` invocation, once: start the recorder, create one container from a mirror-prefixed name (a tag of a
    local image, so no pull), stop, and compare the record and the checker's verdict. A shared daemon may record other creates: the
    probe's name must be among them."""
    def test_a_container_created_under_the_recorder_is_recorded_and_compared(self):
        local = os.environ.get("RECORDER_REAL_IMAGE", "hello-world")
        name = "mirror.gcr.io/recorder-probe-3646:1"
        container = f"recorder-probe-3646-{os.getpid()}"
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp, True)
        out = tmp / "started.txt"

        def docker(*argv):
            return subprocess.run(["docker", *argv], capture_output=True, text=True)

        self.assertEqual(0, docker("tag", local, name).returncode)
        self.addCleanup(docker, "rmi", name)
        recorder = [str(TOOLS / "ci-record-started-images.sh")]
        # settle 0: the create races the stream's attach; --since is what makes the record complete
        self.assertEqual(0, subprocess.run(recorder + ["start", str(out)], capture_output=True, text=True,
                                           env=dict(os.environ, CI_RECORD_SETTLE="0")).returncode)
        created = docker("create", "--name", container, name, "true")
        self.addCleanup(docker, "rm", "-f", container)
        self.assertEqual(0, created.returncode, created.stderr)
        deadline = time.time() + 15
        while name not in out.read_text().splitlines() and time.time() < deadline:
            time.sleep(0.2)
        stopped = subprocess.run(recorder + ["stop", str(out)], capture_output=True, text=True)
        self.assertEqual(0, stopped.returncode, stopped.stdout)
        self.assertIn(name, out.read_text().splitlines())
        self.assertIn("recorder-probe-3646:1", check.started_images(out.read_text(), "mirror.gcr.io/"))


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

    def test_the_checker_and_the_pre_pull_select_the_same_rows_for_every_plan(self):
        """The second reader of I1: row_state() (python) and wanted() (bash) are two implementations of one selection; they must agree."""
        listing = "testcontainers/ryuk:0.12.0 *\npostgres:16 mod/a\nmysql:8.0 mod/b\nx.io/y:1 mod/c!\nz:1 mod/a!,mod/d\nboth:1 mod/a!,mod/b\nonly:1 mod/e!\n"
        root = self.tmp / "root"
        tree(root, {}, listing)
        entries = check.listed(root)
        for plan in (["mod/a"], ["mod/c"], ["mod/b", "mod/c"], ["mod/d"], ["mod/a", "mod/c"], ["mod/e"], ["none"], []):
            self.log.write_text("")
            self.assertEqual(0, self.run_with(listing, "".join(m + "\n" for m in plan)).returncode)
            pulled = {check.canonical(line[len("pull "):], "mirror.gcr.io/") for line in self.log.read_text().splitlines()}
            selected = {check.canonical(i) for i, m in entries.items() if check.row_state(m, set(plan)) == check.LISTED}
            self.assertEqual(selected, pulled, plan)

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

    def test_include_excluded_switch_pulls_a_planned_excluded_module_only_when_set(self):
        listing = "testcontainers/ryuk:0.12.0 *\nx.io/y:1 mod/c!\npostgres:16 mod/a\n"
        result = self.run_with(listing, "mod/c\n", CI_PREPULL_INCLUDE_EXCLUDED="1")
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual(["pull mirror.gcr.io/testcontainers/ryuk:0.12.0", "pull x.io/y:1"], self.log.read_text().splitlines())
        self.log.unlink()
        result = self.run_with(listing, "mod/c\n", CI_PREPULL_INCLUDE_EXCLUDED="0")
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
