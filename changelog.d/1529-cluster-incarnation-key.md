### Added (2026-09-28 — #1529 part 1: cluster incarnation key)
- **The cluster now has one committed authority for its lineage and incarnation.**
  - New cluster-state `AetherKey.ClusterIncarnationKey` (singleton) holds
    `AetherValue.ClusterIncarnationValue(String lineageId, long incarnation)`, on wire tags 1709/1710.
  - It is backed up, so a restore carries it.
  - Read it with `ClusterIncarnation.current(KVStore)`, which returns 0 before genesis.
- **Genesis.** The leader mints incarnation 1 with a fresh ULID lineage when none is committed
  (`ClusterIncarnationRegistrar`: leader-armed, retried on bounded backoff, confirmed by re-read).
  - Racing mints resolve first-wins, and a Put through the fence must be the immediate successor
    `[mechanism: VersionFenced lost-update fence in the KV applier]`. A Remove is not fenced, and a
    restore bypasses the fence on purpose.
- **Restore.** `ClusterIncarnation.restoreCommands(restored, highestRecordedForLineage)` keeps the restored
  lineage and commits `max(restored, highest recorded for that lineage) + 1`. It never goes back below a
  recorded incarnation and never reuses one, even over a genesis the restarted cluster minted first.
  - The restore path and the scan that supplies the highest recorded incarnation are #1533
    `[design intent — unverified]`.
  - **Residual:** an incarnation that ran but whose key never reached the backup before a crash is
    invisible to the floor and can be reused `[unverified]`. #1532 narrows the window by flushing an
    incarnation change immediately.
- Unit-tested against a real KV applier; no multi-node verification yet.
- Part 2 (incarnation-first epoch ordering, worker revision latches, replay rewind) is separate.
