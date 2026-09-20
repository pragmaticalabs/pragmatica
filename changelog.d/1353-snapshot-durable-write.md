### Fixed (2026-09-20 — #1353: metadata snapshots were written without fsync and `LATEST` was overwritten in place)

- **A snapshot and its `LATEST` pointer now reach disk complete or not at all, and snapshot writes
  are serialised.**
  `DefaultSnapshotManager` (`integrations/storage`) wrote `snapshot-<epoch>.dat` and `LATEST` with
  `Files.writeString(CREATE, TRUNCATE_EXISTING)`: no fsync, no rename, the pointer truncated in
  place. A write cut part-way left a torn snapshot under its final name — newest by epoch, so
  retained longest — or half a file name in `LATEST`, and a boot then restored NOTHING although a
  complete snapshot sat beside it. Both files are now written to a fixed sibling partial
  (`snapshot.partial`, `LATEST.partial`), `FileChannel.force(true)`d, and renamed over the target in
  one rename (`FileOps.moveAtomic`, the #676 shape); a failure at either step removes the partial.
  `forceSnapshot()` now runs under a `ReentrantLock` — the `maybeSnapshot` CAS only coalesced ticks,
  while the HTTP route `StorageRoutes.triggerSnapshot` calls `forceSnapshot()` on another thread;
  with fixed partial names two unserialised writers truncate each other's partial and rename each
  other's bytes under their own epoch name (rev1365 measured 3 torn + 26 misnamed files from 4 × 25
  concurrent calls on the unlocked fix). A waiting loser, not a coalescing one: a forced snapshot
  must reflect state at or after the call, and the route reports `lastSnapshotEpoch` right after.
  `[unverified: power loss — the rename's directory entry is not fsynced, the same bound as
  GitBackedPersistence; what is pinned is torn-file behaviour, through a fault seam on the sync step]`
- **A torn newest snapshot falls back to another retained one — newest complete first — instead of to nothing.**
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
- Pinned by `SnapshotDurableWriteTest`: `forceSnapshot_concurrentCallers_writeOneAtATime` (a
  depth-measuring sync seam across 4 threads, max depth 1; red without the lock) and
  `forceSnapshot_concurrentCallers_leaveOnlyCompleteCorrectlyNamedFiles` (rev1365's probe with the
  production writer: every surviving file validated alone, none torn, none misnamed);
  `forceSnapshot_syncsEachPartialExactlyOnce` (red with the sync step dropped);
  `forceSnapshot_latestWriteInterrupted_previousSnapshotStillRestores`
  and `forceSnapshot_snapshotWriteInterrupted_leavesNoTornFileUnderAnyName` (red on the seam-only
  base with the defect's own shape: a torn `snapshot-000002.dat` on disk; a restore that returns
  nothing), `forceSnapshot_renameFails_previousLatestSurvives` (red with `moveAtomic` swapped for
  `moveReplace`), `restoreFromLatest_newestSnapshotTorn_fallsBackToPreviousRetained`,
  `restoreFromLatest_latestPointerTorn_fallsBackToNewestRetained`,
  `restoreFromLatest_twoNewestTorn_fallsBackToThird` (red on the unmodified base),
  `restoreFromLatest_onlySnapshotTorn_returnsNoneAndWarns`, `restoreFromLatest_firstBoot_noSnapshotAndNoWarn`.
  `[verified: fsync(2) observed by rev1365 under strace on Linux — 48 at head (24 per partial name),
  0 with the sync step removed; the suite pins that the sync step runs once per partial, not that it
  calls force(true) rather than force(false)]`
