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

    def test_verify_fails_when_sums_is_missing_or_empty(self):
        self.assertNotEqual(0, run("verify", str(self.dir)).returncode)
        (self.dir / "SHA256SUMS").write_text("")
        self.assertNotEqual(0, run("verify", str(self.dir)).returncode)

    def test_generate_fails_on_an_empty_directory(self):
        empty = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, empty)
        self.assertNotEqual(0, run("generate", str(empty)).returncode)


class WorkflowTests(unittest.TestCase):
    text = WORKFLOW.read_text()

    def test_no_path_based_sha256sum_remains(self):
        self.assertNotIn("Generate JAR checksums", self.text)
        self.assertNotIn("target/jbct.jar\n            aether/cli/target/aether.jar\n            aether/node/target/aether-node.jar\n"
                         "            aether/forge/forge-core/target/aether-forge.jar\n            > SHA256SUMS", self.text)

    def test_checksums_job_runs_after_every_asset_producing_job(self):
        job = self.text.split("\n  release-checksums:")[1].split("\n  docker-publish:")[0]
        needs = re.search(r"needs: \[([^\]]*)\]", job).group(1)
        self.assertEqual({"build-and-release", "build-macos-dist", "build-linux-arm64-dist"},
                         {n.strip() for n in needs.split(",")})
        self.assertIn("release-checksums.sh generate", job)
        self.assertIn("release-checksums.sh verify", job)
        self.assertIn("gh release view", job)

    def test_first_job_no_longer_uploads_sums(self):
        first = self.text.split("\n  build-macos-dist:")[0]
        self.assertNotIn("SHA256SUMS", first)


if __name__ == "__main__":
    unittest.main()
