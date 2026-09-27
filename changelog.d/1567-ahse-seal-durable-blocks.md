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
- **Every write now lands on every durable tier.** A write-through put must succeed on every tier that
  reports `isDurable()` (only the local-disk tier in rc4) and on the last tier; other tiers stay best-effort
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
  `streams` fallback a directly constructed node still degrades to when its disk tier cannot be created)
  refuses to seal; its segments stay pending and the WAL keeps their records. Recovery: restore the disk
  tier and restart the node.
- **A put no longer hands out an id before its block has landed.** A second put of the same content used
  to deduplicate onto the first put's claim while that write was still in flight, so a cursor ref or an
  entity checkpoint pointer committed to KV could name bytes that never landed. It now waits for the
  in-flight write; if that write fails, it claims and writes the block itself.
  [verified: integrations/storage/src/test/java/org/pragmatica/storage/StorageInstanceWriteRaceTest.java]
- **A required write that fails part-way leaves no unreachable copy.** When the last (shared) tier fails
  after the local disk took the block, the disk copy is removed before the claim is released, because
  record-driven garbage collection would never find it; a removal that itself fails is logged at WARN.
- **A production node refuses to boot when its streams block tier cannot be created**
  (`StreamDiskTierUnavailable`), as it already did for an unwritable WAL directory, unless non-durable
  streams are opted into with `-Daether.allowNonDurableStreams=true`. Recovery: fix the mount or
  permissions under the stream data directory and restart.
- **Append logs can be inspected without being changed**, and recovery is loud. Listing a storage
  instance's logs, inspecting a log's low/head offsets, and reading its owner-epoch history change no byte
  or timestamp on the volume; cutting a torn tail happens only on an explicit open, with a WARN naming the
  log, the byte range and the last valid offset, and a `TornTailSink` hook for a cluster event (not yet
  wired: the `OperatorWarning` event is #1574). Constructing a `LocalDiskTier` now only reads its directory;
  creating the directory and sweeping partial blocks left by a crashed write happen in an explicit `open()`,
  which the node calls for the volumes it owns. Each log gains a durable owner-epoch history (`<log>.epochs`,
  written by temp, force, rename and directory force) for ranking replicas after an ownership move; the
  WAL record format is unchanged, and nothing records epochs yet.
- `AppendLog` and its test were relicensed from BUSL-1.1 to Apache-2.0 per owner ruling as they moved
  into `integrations/storage`.
- `FileOps` gains `writeBytesForced`, `forceDirectory` and `createDirectoriesDurable`.
- `RemoteTierConfig` validates its inputs through `Option` rather than null checks, which reports every invalid
  input at once instead of the first.
