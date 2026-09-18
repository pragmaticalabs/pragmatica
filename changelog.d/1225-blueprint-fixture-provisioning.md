### Fixed (2026-09-18 — #1225: blueprint fixtures were ambient state, not a provisioned fixture set)
- **`build.sh` now builds the example blueprint fixtures.** `examples/url-shortener` (1.0.0) and
  `examples/url-shortener-v2` (1.0.1) are deliberately excluded from `examples/pom.xml` — they carry
  independent versions that do not track the platform version, and that exclusion is correct. The
  comment there says to build them separately after platform install; nothing ever did. Their
  `-blueprint.jar` artifacts therefore existed only where somebody had once run Maven by hand, so
  `06-deployment` passed on residue in a long-lived `~/.m2/repository` and failed completely against
  a clean or per-worktree one — every blue-green, canary and rolling test returning
  `Artifact not found: org.pragmatica.aether.example:url-shortener:1.0.0:jar`.
- **A documented manual step that nothing performs is not a fixture.** This stayed invisible while the
  CLI hard-coded `~/.m2/repository` (#1223); fixing that resolution is what exposed it.
- `aether/tests/integration/lib/verify-blueprint-fixtures.sh` **refuses to start a run whose fixtures
  are absent.** It derives the required coordinates by scanning the suites, so a suite that adds a
  blueprint extends the checked set by construction — a hard-coded list is what produced this bug.
  It resolves the local repository the same way `MavenLocalRepoLocator` does, honouring
  `-Dmaven.repo.local` from `AETHER_JAVA_OPTS`: checking a different repository than the deploy reads
  would report a clean preflight and then deploy nothing, which is the same defect in another costume.
- It fails on **"not built into this repository"**, not on "has no `-blueprint.jar`" — some referenced
  coordinates are slice artifacts that legitimately ship only a plain jar, and requiring the blueprint
  classifier produced a false positive on `test-entity-entity-slice`. A zero coordinate count is
  treated as a broken scanner rather than a clean result.
- Wired into `run-tests.sh` **after** the build step and run even under `--skip-build`, which is
  precisely the path where a fixture gap survives. Previously a missing fixture surfaced as an HTTP
  500 four suites deep, one paid cloud provision cycle after it was knowable.
