### Fixed (2026-09-20 — #1353: metadata snapshots were written without fsync and `LATEST` was overwritten in place)

- **A snapshot and its `LATEST` pointer now reach disk complete or not at all.**
  `DefaultSnapshotManager` (`integrations/storage`) wrote `snapshot-<epoch>.dat` and `LATEST` with
  `Files.writeString(CREATE, TRUNCATE_EXISTING)`: no fsync, no rename, the pointer truncated in
  place. A write cut part-way left a torn snapshot under its final name — newest by epoch, so
  retained longest — or half a file name in `LATEST`, and a boot then restored NOTHING although a
  complete snapshot sat beside it. Both files are now written to a fixed sibling partial
  (`snapshot.partial`, `LATEST.partial`), `FileChannel.force(true)`d, and renamed over the target in
  one rename (`FileOps.moveAtomic`, the #676 shape); a failure at either step removes the partial.
  `[unverified: power loss — the rename's directory entry is not fsynced, the same bound as
  GitBackedPersistence; what is pinned is torn-file behaviour, through a fault seam on the write]`
- **A torn newest snapshot falls back to the previous retained one instead of to nothing.**
  `restoreFromLatest()` tries the file `LATEST` names; when it is missing, torn or fails its hash
  check, the retained snapshots are tried newest-first and the first complete one is restored, at
  WARN naming both files and pointing at the runbook. A torn snapshot is never restored. The read
  path rewrites nothing: `LATEST` is repointed by the next snapshot write, and the unreadable file
  stays as evidence until ordinary retention removes it. With #1345, WAL truncation reads the
  snapshot through this same path, so truncation and recovery agree on which file is current.
  No complete snapshot at all still yields none — readiness is still signalled unconditionally
  (#1013, open).
- **Operator action documented** in `aether/docs/operators/runbooks/backup-recovery.md`, "Storage
  metadata snapshots".
- Pinned by `SnapshotDurableWriteTest`: `forceSnapshot_latestWriteInterrupted_previousSnapshotStillRestores`
  and `forceSnapshot_snapshotWriteInterrupted_leavesNoTornFileUnderAnyName` (red on the seam-only
  base with the defect's own shape: a torn `snapshot-000002.dat` on disk; a restore that returns
  nothing), `forceSnapshot_renameFails_previousLatestSurvives` (red with `moveAtomic` swapped for
  `moveReplace`), `restoreFromLatest_newestSnapshotTorn_fallsBackToPreviousRetained`,
  `restoreFromLatest_latestPointerTorn_fallsBackToNewestRetained`,
  `restoreFromLatest_twoNewestTorn_fallsBackToThird` (red on the unmodified base),
  `restoreFromLatest_onlySnapshotTorn_returnsNoneAndWarns`, `restoreFromLatest_firstBoot_noSnapshotAndNoWarn`.
  `[unverified: the fsync hunk — no userland observation distinguishes a forced write from an
  unforced one; removing it reddens nothing]`
