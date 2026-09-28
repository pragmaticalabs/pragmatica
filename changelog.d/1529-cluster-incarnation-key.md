### Added (2026-09-28 — #1529 part 1: cluster incarnation key)
- **The cluster now has one committed authority for its lineage and incarnation.**
  - New cluster-state `AetherKey.ClusterIncarnationKey` (singleton) holds
    `AetherValue.ClusterIncarnationValue(String lineageId, long incarnation)`, on wire tags 1709/1710.
  - It is backed up, so a restore carries it.
  - Read it with `ClusterIncarnation.current(KVStore)`, which returns 0 before genesis.
- **Genesis.** The leader mints incarnation 1 with a fresh ULID lineage when none is committed
  (`ClusterIncarnationRegistrar`: leader-armed, retried on bounded backoff, confirmed by re-read).
  - The value is version-fenced, so racing mints resolve first-wins, and an incarnation can only move
    to its immediate successor
    `[mechanism: VersionFenced lost-update fence in the KV applier]`.
- **Restore.** `ClusterIncarnation.restoreCommands(restored)` keeps the restored lineage and commits the
  next incarnation, even over a genesis the restarted cluster minted first. The restore path itself is
  #1533 `[design intent — unverified]`.
- Unit-tested against a real KV applier; no multi-node verification yet.
- Part 2 (incarnation-first epoch ordering, worker revision latches, replay rewind) is separate.
