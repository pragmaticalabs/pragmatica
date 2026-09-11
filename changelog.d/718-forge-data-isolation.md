### Fixed (2026-09-11 — #718: every Forge on a host wrote to one machine-wide data directory, and the tutorial documented neither the path nor the sharing)
- **`./run-forge.sh`, run exactly as `aether/docs/getting-started.md` documents it, wrote to a single
  machine-wide `~/.aether/forge-data`.** The path was derived from the user's home directory alone, so
  "a different project" and "the same project" were indistinguishable to Forge. On 2026-09-11 a run
  following the tutorial verbatim in a scratch project destroyed 32,167 files of an unrelated
  investigation's durable state; 25 survived. The documented procedure was followed exactly, which is
  what makes this a code defect rather than a user error. The tutorial's own verification appendix
  shows the omission was deliberate: a 2026-07-24 pass confirmed data "lands under
  `$AETHER_HOME/forge-data`" and recorded that it was "not written into the tutorial body since it
  wasn't a prior claim there".
- **The data directory is now scoped to the project**, defaulting to `<directory of the --config
  file>/.aether/forge-data`. Two projects cannot collide, because the path is a function of the
  project rather than a check that might miss. Every `run-forge.sh` — scaffold and examples alike —
  already passes `--config`, so this needs no change to any script.
  `[verified: aether/forge/forge-core/src/test/java/org/pragmatica/aether/forge/ForgeDataDirTest.java`
  → `Scoping.location_differsBetweenTwoProjects]`
- **Restart still reuses the same directory**, so #515's crash-durable stream WAL is unaffected: the
  isolation is not bought with a fresh directory per run.
  `[verified: ForgeDataDirTest.Scoping.location_isStableAcrossRunsOfOneProject]`
- **A run that cannot prove it owns populated durable state now refuses to start**, exits non-zero, and
  names both projects. Forge records the owning project in a `.forge-owner` file and, before creating
  anything, refuses when the directory is populated and owned by someone else or populated with no
  owner recorded — the exact shape that destroyed the reproducer. Both refusals state that nothing has
  been written or deleted, because the loss was discovered only by noticing files were already gone.
  `[verified: ForgeDataDirTest.OwnershipGuard — 8 cases, incl. inspect_refuses_whenStateHasNoRecordedOwner
  and inspect_refusalNamesBothProjectsAndStatesNothingWasDeleted]`
- **Reuse the guard permits is announced rather than silent**, with the inherited entry count, before
  the cluster starts. `[verified: ForgeDataDirTest.OwnershipGuard.inspect_announcesReuse_whenStateIsOwnedByThisProject]`
- **`AETHER_HOME` is honoured but demoted to config-less runs**, and `AETHER_FORGE_DATA` is added as the
  single-meaning override. `[mechanism: install.sh:21 and upgrade.sh:11 read AETHER_HOME as the INSTALL
  directory (`INSTALL_DIR="${AETHER_HOME:-$HOME/.aether}"`), so scoping run data on it would silently
  re-share state across every project of anyone who exports it to put `$AETHER_HOME/bin` on PATH.]`
  The container entrypoint runs without `--config` and so keeps its present location unchanged.
- **`jbct init` scaffolds `.aether/` into `.gitignore`**, and the repository ignores it for
  `examples/*/`. `[verified: SliceProjectInitializerTest.initialize_validParams_ignoresForgeDataDir]`
- `getting-started.md` gains a "Where Forge keeps its data" section stating the path, that it is
  per-project, that restart reuses it, that Forge never clears it, and what the two refusals mean;
  `forge-guide.md` gains the full precedence table.
- **Limits, stated rather than implied.** `[unverified: no live two-project Forge run was executed —
  the properties are pinned at the resolver and guard, not end-to-end through a started cluster.]`
  `[unverified: the ownership check is time-of-check/time-of-use — it inspects before anything is
  created, so a process claiming the directory inside that window is not caught. It is not a lock,
  and the same limitation is stated on the sibling QUIC port preflight.]` An existing machine-wide
  `~/.aether/forge-data` is never read by project runs, never adopted and never deleted; it is left on
  disk. Corruption of the state itself is a different layer (#1012) and is untouched here.
