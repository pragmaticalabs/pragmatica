### Fixed (2026-09-17 — #1222: `artifacts push` reads a local repository nobody configured)
- **`aether artifacts push` hard-coded `${user.home}/.m2/repository` with no override.** Both
  `findBlueprintJar` and `findSliceJar` built the path from `user.home` directly, so neither
  `-Dmaven.repo.local` nor a `<localRepository>` in `settings.xml` had any effect. Both now resolve
  through `MavenLocalRepoLocator.findLocalRepository()` — the same chain `LocalRepository` and
  `RemoteRepository` already use — so the CLI and the runtime stop disagreeing about where the local
  repository is. No new configuration surface: the standard Maven settings simply take effect.
- **The failure mode is a silent stale push, not a missing file.** Where a build is directed at a
  non-default repository — a per-worktree `.m2-local`, or any `settings.xml` with a custom
  `localRepository` — the CLI kept reading the default one, found *a* jar there, and pushed it. The
  artifacts deployed were not built from the commit under test, and nothing reported an error,
  so a green run carried exactly as much evidence as a red one.
- **`MavenLocalRepoLocatorTest` asserted nothing.** It printed the resolved path and returned, so it
  passed for every possible implementation, including one that ignores `maven.repo.local` entirely.
  It now pins the precedence order, that an empty property is rejected rather than resolving the
  repository to `""`, and that the fallback expands `~` and `${user.home}` instead of returning them
  literally.
- `cli` gains `CliLocalRepositoryTest`, which pins the override for both finders and carries a
  negative control asserting the fallback still lands under the default repository — without it,
  a resolver that merely echoed the last value set would pass both positive cases.
- **`aether/script/aether.sh` now passes `${AETHER_JAVA_OPTS:-}` through to the JVM**, matching the
  installed launcher at `~/.aether/bin/aether`. Without it a dev run had no way to set the property
  the fix above makes meaningful.
