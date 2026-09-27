### Fixed (2026-09-27 — #1567: sealed stream segments were not fsynced, but the WAL was truncated past them)
- **A power loss could lose acknowledged stream records.** A sealed segment's block was written to the local
  disk without `fsync`, and on the `streams` instance the local disk was not even a required tier: the
  write-through path treated the LAST tier (the in-memory DHT tier) as the durable one and absorbed a
  local-disk failure as a cache miss (#910). The 30 s truncation tick then dropped the segment's records
  from the partition WAL once the metadata snapshot named its ref, so after a power loss or kernel crash
  the snapshot could point at a block that was torn, empty, or never on disk, with the WAL no longer
  holding the records.
- **The partition WAL is now the storage engine's append log, and sealing is a storage operation.**
  `PartitionWal` moved into `integrations/storage` as `AppendLog`, with its fsync, framing, fail-stop and
  recovery code unchanged; a `StorageInstance` opens its logs with `openLog(name)` under its own log root
  (the stream WAL directory, so the on-disk layout is unchanged). `StorageInstance.seal(log, from, to, ref,
  block)` makes the block durable, then records the ref, then advances the log's seal bound, and
  `AppendLog.truncate` never discards past that bound, whatever the caller asks. The ordering lives in the
  storage engine, not in its callers. [verified: integrations/storage/src/test/java/org/pragmatica/storage/StorageInstanceSealTest.java]
  (unit level: injected force seams that observe the ref and the log bound at each force; not a live
  power-loss run).
- **Every write now lands on every durable tier.** A write-through put must succeed on the last tier AND on
  every tier that reports `isDurable()` (only the local-disk tier in rc4); other tiers stay best-effort
  cache. This covers cursor commits and entity checkpoints too, not only seals.
  [verified: integrations/storage/src/test/java/org/pragmatica/storage/StorageInstanceSealTest.java]
- **Every local-disk block is durable before its put resolves:** the partial file is forced with its
  metadata, renamed into place, and the block directory is forced; a shard directory created for it is made
  durable in its parent. Every block, not only referenced ones, because deduplication can make any block
  reachable from a later ref without rewriting it. Cost: two fsyncs per block written.
- **New log files and renamed snapshots survive a power loss.** `AppendLog.open` forces the directory when
  it creates a log file (the group-commit fsync never covered the entry naming the file), and the metadata
  snapshot's directory is forced after each rename, so a restart cannot resurface an older snapshot whose
  sealed bound is below what the WAL was already truncated to.
  [verified: integrations/storage/src/test/java/org/pragmatica/storage/AppendLogTest.java,
  integrations/storage/src/test/java/org/pragmatica/storage/SnapshotDurableWriteTest.java]
- **Bounds that remain.** Ref durability is still bounded by the metadata-snapshot lag (#1345) until
  metadata is journaled (#1570). The log's seal bound is in memory: after a restart nothing is truncated,
  and the WAL grows, until the first seal of the new process. An instance without a local-disk tier (the
  `streams` fallback when its disk tier cannot be created) now refuses to seal; its segments stay pending
  and the WAL keeps their records. Recovery: restore the disk tier and restart the node.
- `FileOps` gains `writeBytesForced`, `forceDirectory` and `createDirectoriesDurable`.
