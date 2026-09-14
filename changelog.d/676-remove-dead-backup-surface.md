### Removed (2026-09-14 — #676: the backup API/CLI could never be enabled; git-backed persistence is the backup)
- **Decision: delete, not wire.** `BackupService` has only ever had stubs for implementations — `disabled()`
  from its introduction in `1dc452bf0`, plus a `NoOpBackupService` in `aether/lb` that lived one day on an
  archived branch (`6dcfa57e3`..`00a5c0f91`, reachable only from the 2026-09-10 archive bundle) — and both
  node-construction sites passed `disabled()` unconditionally, so
  `POST|GET /api/v1/backups`, `POST /api/v1/backups/restore`, `aether backup create|restore|list` and
  `aether backups trigger|list|restore` answered `backup-disabled` in every configuration that has ever shipped.
  The ticket's open question (feature flag awaiting cutover, or regression?) is answered by the history: neither —
  a scaffold shipped with its stub and no cutover was ever built. Wiring it honestly is not a route: a real
  `backupNow`/`restore` needs leader-coordinated snapshot and restore against live consensus (the #660 floor
  reads this persistence at sync adoption), which is feature work, not a wire-or-delete item. So the dead
  surface is removed and the real mechanism is documented and hardened: `BackupService`, `BackupRoutes`, the
  three `ManagementRoute` constants, their permission entries (`ManagementRoutePermissions`,
  `RoutePermissionRegistry`), both CLI command trees, the `ManageableNode.backupService()` accessor, the
  `AuditLog.backupCreated/backupRestored` writers and the producer-less `OperationalEvent.BackupCreated/
  BackupRestored` records with their aggregator handlers are gone. `ClusterEvent.BackupCreated/BackupRestored`
  stay: wire tags 258/259 pin them and retiring a tag is a codec-table change, filed separately; they are
  documented as having no producer. `[backup]` config is unchanged — it is read by the real path.
  [mechanism: the removal is enforced by the compiler and the cli-docs gate; `git grep` for `BackupService`,
  `BACKUP_TRIGGER|BACKUPS_LIST|BACKUP_RESTORE`, `/api/v1/backups`, `aether backup` across `*.java` and live
  `*.md` returns only the #676 annotations]
- **`GitBackedPersistence` no longer corrupts the previous snapshot on an interrupted save.** `state.toml` was
  written in place with `TRUNCATE_EXISTING`; a save that failed after the open left a truncated file, and
  `load()` decoded the half base64 line into an EMPTY snapshot — a node restarted after a mid-save crash would
  have loaded nothing and said so to no one. The snapshot is now written to `state.toml.partial`, fsynced
  (`FileChannel.force(true)`) and renamed over `state.toml` in one `rename(2)` (new `FileOps.moveAtomic` =
  `REPLACE_EXISTING` + `ATOMIC_MOVE`; the partial is a sibling of `state.toml` in `[backup] path`, so the rename
  never crosses a filesystem), so the state file only ever holds a complete snapshot; a failed save deletes its
  partial. `FileOps.moveReplace` (no `ATOMIC_MOVE`, which the first round used) is NOT that: the JDK unlinks the
  target before the rename, so a crash between the two left no `state.toml` at all and a failed rename plus the
  partial cleanup destroyed both copies. [verified:
  `GitBackedPersistenceTest#save_interruptedMidWrite_keepsThePreviousSnapshotLoadable` — a write seam that
  stores half the new content and fails: red at the base with `expected: [1, 2, 3] but was: []`, green after;
  `load()` returns the previous snapshot at its phase, only `state.toml` and `.git` remain, one commit]
  [verified: `FileOpsTest#moveAtomic_renameFails_targetSurvives` — source and target in sibling directories,
  the source's directory read-only so the rename fails while an unlink of the target would succeed: the target
  survives; red without `ATOMIC_MOVE` (`target GONE`)] [unverified: that the `GitBackedPersistence` call site
  itself is atomic — established by reading `UnixFileSystem.move` (single `rename` under `ATOMIC_MOVE`, `unlink`
  then `rename` without it), not by a test: the partial and `state.toml` share a directory, so no non-root
  fault injection makes the rename fail while the unlink succeeds] [unverified: the fsync itself — the seam
  replaces the production writer, so the `force(true)` call is exercised only by the healthy-write tests,
  never by a power-loss probe; no directory fsync follows the rename, matching the `PartitionWal` precedent]
- **Docs corrected to the real mechanism** (`backup-recovery.md`, `configuration.md`, `management-api.md`,
  `cli.md`, `feature-catalog.md` row 206, `guarantees.md` §1a, `management-api-versioning-spec.md`): saves
  happen on lifecycle transitions only (quorum-loss pause, reconfigure, graceful stop) and never on commit or
  on a timer — `[backup] interval` is parsed and read by nothing and is now documented as accepted-and-ignored;
  the file is `# Phase: N` + base64 of the binary KV snapshot, so `git diff` shows opaque blobs, not per-key
  changes; listing is `git log`, restore is a `git checkout <commit> -- state.toml` with all nodes stopped.
  [mechanism: the trigger sites are the four `persistence.save` calls in `RabiaEngine`; the payload hooks are
  `AetherNode::snapshotToBase64`/`::base64ToSnapshot`]
- Out of scope, noted for the CTO: `BackupConfig.interval` is a dead config key (#675's class); retiring wire
  tags 258/259; `GitBackedPersistence.load()` still turns a parse failure into "no saved state" (`.option()`),
  so a corrupted `state.toml` from any other cause boots empty rather than refusing; `LocalDiskTier.writeThenRename`
  (`integrations/storage`) renames its partial over the block with the same non-atomic `moveReplace` this round
  replaced here — same hazard, separate module.
