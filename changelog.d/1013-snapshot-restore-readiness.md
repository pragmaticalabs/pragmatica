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
  gave a tier that fails to build. Readiness is simply never signalled and the half-built setup is
  discarded as the node aborts — the refusal is the whole operator-visible surface, not a gate left
  in `LOADING_SNAPSHOT` for anyone to read
  `[verified: aether/node StorageFactorySnapshotRestoreTest — a torn only snapshot, a dangling
  LATEST, and a directory with no LATEST and only a torn file each refuse createAll and the streams
  entry; before the fix all four signalled SNAPSHOT_LOADED/isReadReady=true]`
  `[verified: integrations/storage SnapshotDurableWriteTest — the manager's three outcomes, including
  the unlistable-directory failure and the missing-directory absence]`.
- `[unverified: unmounted volume]` **An absent snapshot directory still reads as a first boot even
  when it is absent because the volume never mounted.** Not closed by this change, and named here so
  the gap is not mistaken for coverage. Refusing on an absent data root was implemented and reverted:
  that state is the ORDINARY one wherever `/data` is not writable, where the disk tier degrades to
  memory+DHT by design, so refusing contradicts "node boot never fails on an unmountable data dir".
  The discriminator lives in `StorageFactory`, which knows whether the disk tier armed or degraded;
  threading that bit into `SnapshotConfig` is the fix, and it needs its own ticket.
- **The aggregate "nothing restorable" WARN is kept, so a running node still reports torn snapshots.**
  On the boot path the failure becomes a named refusal, but snapshots that tear AFTER a good boot
  (a disk that fills) leave no boot to refuse: every WAL-truncation tick then fails. The manager
  WARNs its aggregate verdict and `DurableSealedOffsetSource` WARNs that the tick kept the WAL
  `[verified: integrations/storage SnapshotDurableWriteTest#restoreFromLatest_onlySnapshotTorn_warnsTheAggregateVerdict;
  aether-stream StreamPartitionManagerRestartAfterCompactionTest#fromLatestSnapshot_snapshotOnDiskUnreadable_warnsTheTruncationTick
  — both assert both arms of the same appender, so neither can pass vacuously]`.
- `DurableSealedOffsetSource.fromLatestSnapshot` (WAL truncation bound, #1345) keeps treating a failed
  read as "nothing durable, truncate nothing" — a truncation tick, not the boot path — and now says so.
