### Fixed (2026-09-21 — #1013: a failed or absent snapshot restore signalled readiness unconditionally)
- **A storage instance whose metadata snapshot could not be restored came up read-ready on EMPTY
  metadata**, indistinguishable from a first boot: `StorageFactory.restoreAndSignalReady` called
  `snapshotLoaded()` whatever `restoreFromLatest()` returned, and the manager folded "nothing on disk"
  and "something on disk, none of it readable" into the same `none()` (with a WARN for the second only
  when `LATEST` was the part still present). `SnapshotManager.restoreFromLatest()` now returns
  `Result<Option<MetadataSnapshot>>` with the three outcomes kept apart: success with the snapshot
  (#1353's fallback to an older retained file included, WARNed as before); success with none only when
  NEITHER `LATEST` nor any `snapshot-*.dat` is on disk, established by looking at the directory, not
  inferred from a failed read (a directory that exists but cannot be listed is a failure); failure
  (`SnapshotError.NothingRestorable`) when something is on disk and nothing restores. On that failure
  `createAll` refuses the boot naming the instance and the file, through the same `Result` path #253
  gave a tier that fails to build, and the readiness gate stays in `LOADING_SNAPSHOT`
  `[verified: aether/node StorageFactorySnapshotRestoreTest — a torn only snapshot, a dangling
  LATEST, and a directory with no LATEST and only a torn file each refuse createAll and the streams
  entry; before the fix all four signalled SNAPSHOT_LOADED/isReadReady=true]`
  `[verified: integrations/storage SnapshotDurableWriteTest — the manager's three outcomes, including
  the unlistable-directory failure and the missing-directory absence]`.
- `DurableSealedOffsetSource.fromLatestSnapshot` (WAL truncation bound, #1345) keeps treating a failed
  read as "nothing durable, truncate nothing" — a truncation tick, not the boot path.
