"""Pins for the release checksums (#2235): SHA256SUMS covers EVERY published asset, by bare name, and a check that can go red.

The rc4 release published 23 assets and listed 4 of them under `target/` build paths, so no platform archive could be verified.
`scripts/release-checksums.sh` is executed here against a directory shaped like the assembled release; the release workflow is read to
pin that the sums are produced by a job that runs after every asset-producing job, not beside the first one."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "release-checksums.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"

JARS = ["jbct.jar", "aether.jar", "aether-node.jar", "aether-forge.jar"]
ARCHIVES = [f"aether-{c}-1.0.0-rc5-{p}.{e}" for c in ("cli", "forge", "node")
            for p in ("darwin-arm64", "linux-amd64", "linux-arm64") for e in ("tar.gz", "zip")]
ASSETS = JARS + ARCHIVES


def run(*args):
    return subprocess.run(["bash", str(SCRIPT), *args], capture_output=True, text=True)


class ChecksumTests(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir)

        for name in ASSETS:
            (self.dir / name).write_text("content of " + name)

    def sums(self):
        return (self.dir / "SHA256SUMS").read_text().splitlines()

    def listing(self, names):
        path = self.dir.parent / (self.dir.name + ".listing")
        path.write_text("\n".join(names) + "\n")
        self.addCleanup(path.unlink)
        return path

    def test_generate_covers_every_asset_by_bare_name(self):
        self.assertEqual(0, run("generate", str(self.dir)).returncode)
        self.assertEqual(22, len(self.sums()))
        self.assertEqual(sorted(ASSETS), sorted(re.sub(r"^[0-9a-f]{64}  ", "", line) for line in self.sums()))
        self.assertFalse(any("/" in line for line in self.sums()))
        self.assertEqual(0, run("verify", str(self.dir)).returncode)

    def test_generate_does_not_list_itself_on_rerun(self):
        run("generate", str(self.dir))
        run("generate", str(self.dir))
        self.assertEqual(22, len(self.sums()))

    def test_verify_fails_when_an_asset_is_dropped_from_sums(self):
        run("generate", str(self.dir))
        kept = [line for line in self.sums() if not line.endswith("aether-cli-1.0.0-rc5-darwin-arm64.zip")]
        (self.dir / "SHA256SUMS").write_text("\n".join(kept) + "\n")
        result = run("verify", str(self.dir))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("aether-cli-1.0.0-rc5-darwin-arm64.zip", result.stderr)

    def test_verify_fails_against_published_listing_naming_an_asset_the_sums_lack(self):
        run("generate", str(self.dir))
        result = run("verify", str(self.dir), str(self.listing(ASSETS + ["aether-extra.tar.gz", "SHA256SUMS"])))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("aether-extra.tar.gz", result.stderr)

    def test_verify_passes_against_listing_that_includes_the_sums_file(self):
        run("generate", str(self.dir))
        self.assertEqual(0, run("verify", str(self.dir), str(self.listing(ASSETS + ["SHA256SUMS"]))).returncode)

    def test_verify_fails_on_a_corrupted_asset(self):
        run("generate", str(self.dir))
        (self.dir / "jbct.jar").write_text("tampered")
        self.assertNotEqual(0, run("verify", str(self.dir)).returncode)

    def test_verify_fails_on_build_paths(self):
        run("generate", str(self.dir))
        pathed = [re.sub(r"  jbct\.jar$", "  jbct/jbct-cli/target/jbct.jar", line) for line in self.sums()]
        (self.dir / "SHA256SUMS").write_text("\n".join(pathed) + "\n")
        self.assertNotEqual(0, run("verify", str(self.dir)).returncode)

    def test_verify_fails_on_a_malformed_line_even_though_sha256sum_would_accept_it(self):
        # GNU `sha256sum -c` exits 0 over a file with one garbage line (a warning only); the guard in verify is what fails it.
        run("generate", str(self.dir))
        (self.dir / "SHA256SUMS").write_text("\n".join(self.sums() + ["this is not a checksum line"]) + "\n")
        result = run("verify", str(self.dir))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not '<sha256>  <name>'", result.stderr)

    def test_verify_fails_on_a_sums_entry_for_a_file_the_published_listing_does_not_name(self):
        (self.dir / "stray.bin").write_text("stray")
        run("generate", str(self.dir))
        result = run("verify", str(self.dir), str(self.listing(ASSETS + ["SHA256SUMS"])))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("stray.bin", result.stderr)

    def test_verify_fails_when_sums_is_missing_or_empty(self):
        self.assertNotEqual(0, run("verify", str(self.dir)).returncode)
        (self.dir / "SHA256SUMS").write_text("")
        self.assertNotEqual(0, run("verify", str(self.dir)).returncode)

    def test_generate_fails_on_an_empty_directory(self):
        empty = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, empty)
        self.assertNotEqual(0, run("generate", str(empty)).returncode)


def jobs(text):
    """job name -> job text, for the top-level `jobs:` map of the workflow."""
    body = text.split("\njobs:\n")[1]
    parts = re.split(r"(?m)^  ([a-z0-9-]+):\n", body)
    return {parts[i]: parts[i + 1] for i in range(1, len(parts), 2)}


def steps(job_text):
    """step name -> step text."""
    parts = re.split(r"(?m)^      - name: (.*)\n", job_text)
    return {parts[i]: parts[i + 1] for i in range(1, len(parts), 2)}


PUBLISHING = ("softprops/action-gh-release", "gh release upload", "gh release create", "docker/build-push-action", "push: true")


class WorkflowTests(unittest.TestCase):
    text = WORKFLOW.read_text()
    all_jobs = jobs(text)
    checksums = all_jobs["release-checksums"]

    def test_no_path_based_sha256sum_remains(self):
        self.assertNotIn("Generate JAR checksums", self.text)
        self.assertNotIn("> SHA256SUMS", self.text)

    def test_needs_covers_every_job_that_uploads_release_assets(self):
        uploaders = {name for name, job in self.all_jobs.items()
                     if name != "release-checksums" and ("softprops/action-gh-release" in job or "gh release upload" in job)}
        needs = {n.strip() for n in re.search(r"needs: \[([^\]]*)\]", self.checksums).group(1).split(",")}
        self.assertEqual({"build-and-release", "build-macos-dist", "build-linux-arm64-dist"}, uploaders)
        self.assertEqual(uploaders, needs)

    def test_first_job_no_longer_uploads_sums(self):
        self.assertNotIn("SHA256SUMS", self.all_jobs["build-and-release"])

    def test_expected_set_is_the_published_listing_and_every_asset_is_downloaded(self):
        generate = steps(self.checksums)["Generate SHA256SUMS over every published asset"]
        self.assertIn("gh release view \"$GITHUB_REF_NAME\" --json assets --jq '.assets[].name' > published.txt", generate)
        self.assertIn("gh release download \"$GITHUB_REF_NAME\" --dir release-assets --pattern '*'", generate)
        self.assertLess(generate.index("generate release-assets"), generate.index("verify release-assets published.txt"))

    def test_upload_replaces_on_rerun_and_published_release_is_verified_after_it(self):
        named = steps(self.checksums)
        self.assertIn("gh release upload \"$GITHUB_REF_NAME\" release-assets/SHA256SUMS --clobber", named["Upload SHA256SUMS"])
        after = named["Verify the published release"]
        self.assertIn("grep -x -F SHA256SUMS published-after.txt", after)
        self.assertIn("--pattern '*'", after)
        self.assertIn("verify published-check published-after.txt", after)
        order = list(named)
        self.assertLess(order.index("Generate SHA256SUMS over every published asset"), order.index("Upload SHA256SUMS"))
        self.assertLess(order.index("Upload SHA256SUMS"), order.index("Verify the published release"))

    def test_checksums_job_has_the_token_and_the_write_permission_it_needs(self):
        self.assertIn("GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}", self.checksums)
        self.assertIn("GH_REPO: ${{ github.repository }}", self.checksums)
        self.assertRegex(self.text, r"(?m)^permissions:\n  contents: write")
        self.assertNotIn("permissions:", self.checksums)

    def test_dispatch_is_a_dry_run_that_no_publishing_step_can_reach(self):
        self.assertRegex(self.text, r"(?m)^  workflow_dispatch:")
        self.assertRegex(self.text, r"(?m)^  pull_request:\n    paths:\n      - 'scripts/release-checksums.sh'\n      - '.github/workflows/release.yml'")
        push_only = "if: github.event_name == 'push'"
        reachable = []

        for name, job in self.all_jobs.items():
            if job.lstrip().startswith("if: false"):
                continue

            job_guarded = push_only in job.split("steps:")[0]

            for step_name, step in steps(job).items():
                if any(marker in step for marker in PUBLISHING) and not job_guarded and push_only not in step.split("run:")[0].split("with:")[0]:
                    reachable.append(f"{name}: {step_name}")

        self.assertEqual([], reachable)

    def test_dry_run_builds_assets_and_runs_generate_and_verify_without_a_release(self):
        named = steps(self.checksums)
        dry = named["Generate and verify SHA256SUMS (dry run)"]
        self.assertIn("if: github.event_name != 'push'", dry)
        self.assertIn("release-checksums.sh generate release-assets", dry)
        self.assertIn("release-checksums.sh verify release-assets assembled.txt", dry)
        self.assertNotIn("gh release", dry)
        self.assertIn("pattern: dryrun-assets-*", named["Download dry-run assets"])

        for job in ("build-and-release", "build-macos-dist", "build-linux-arm64-dist"):
            self.assertIn("name: dryrun-assets-", self.all_jobs[job])

    def test_dispatch_version_cannot_leak_into_a_tag_build(self):
        self.assertEqual(4, self.text.count("DRY_RUN_VERSION: ${{ inputs.version || '0.0.0-dryrun' }}"))
        self.assertEqual(4, self.text.count('[[ "$GITHUB_EVENT_NAME" == push ]] || VERSION="$DRY_RUN_VERSION"'))


if __name__ == "__main__":
    unittest.main()
