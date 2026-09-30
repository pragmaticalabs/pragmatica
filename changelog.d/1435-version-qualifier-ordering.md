### Fixed (2026-09-29 — #1435: compareVersions ordered "" < SNAPSHOT and rc10 < rc9)
- **Version patterns compared qualifiers as plain strings**, so `1.0.0` sorted below `1.0.0-SNAPSHOT` and
  `1.0.0-rc10` below `1.0.0-rc9`. Since #1184 that ordering decides whether an `[infra]` slice loads:
  `^1.0.0` silently reused a loaded `1.0.0-SNAPSHOT`, `^1.0.0-rc10` reused `rc9`, and `^1.0.0-SNAPSHOT` was
  refused against a loaded `1.0.0`.
- A release now sorts above its pre-releases, and qualifier tokens compare numerically where numeric (semver
  §11, Maven `ComparableVersion` on these cases). The `[infra]`/`[shared]` rule this gives: a loaded
  pre-release never satisfies a requester of the release it precedes.
  `[mechanism: every caret/tilde/range/comparison pattern matches through VersionPattern.compareVersions; pinned by VersionPatternTest]`
