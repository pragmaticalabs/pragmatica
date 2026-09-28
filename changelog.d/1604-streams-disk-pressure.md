### Fixed (2026-09-28 — #1604: the streams durable tier wedged long-lived nodes)
- **A long-lived node's stream segment disk filled until every seal failed, the WAL could not be truncated,
  and publishes failed.** The disk tier was capped at a hard-coded 4 GiB, and segments sealed before a
  restart came back with no age, so age-based retention never reclaimed them; each restart ratcheted the tier
  up. It is now `[streaming] segment_disk_max_bytes`, derived when unset from the disk holding the stream data:
  40% of the space the tier can use -- the filesystem's usable space plus what the tier already holds, so a
  restart does not shrink it -- at least 1 GiB, at most that space minus 2 GiB. The cap is derived at every
  boot, never remembered, so it follows a resized or moved volume. Retention reads the age of a
  segment rebuilt after a restart from its own block -- the events' time, exactly as a live seal records it --
  so pre-restart segments age out and never early. The reads go at most 8 at a time, a retention pass never
  runs twice at once, and a block that cannot be read (a missing key, a damaged block) is read once, withheld,
  and reported in one WARN.
  [verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/segment/DiskPressureRestartTest.java]
  (unit level: real disk tier, snapshots and garbage collector across a simulated restart; not a multi-node run).
- **Retention deleted a segment's block even when another partition's segment shared it.** Blocks are
  content-addressed and the segment encoding carries no stream or partition, so identical events in two
  partitions share one block; reclaiming one silently destroyed the other's in-retention data. Retention now
  drops only its own ref, and the block goes through garbage collection once nothing references it.
  [verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/segment/SharedBlockRetentionTest.java]
- **Each node now collects its own stream garbage.** Garbage collection was leader-pinned, but a node's stream
  segments live on its own disk; the `streams` collector now runs on every node, while shared-view instances
  keep leader pinning.
- **Disk pressure is loud and bounded.** At 85% of the tier retention warns once per episode and, after
  dropping expired refs, forces a metadata snapshot and collects at once exactly the blocks that snapshot
  records as unreferenced -- no clock is compared, and a failed snapshot collects nothing early. At 95% an
  owner publish is refused with the transient `SEGMENT_TIER_FULL` before it takes an offset, so the WAL disk
  does not fill with records that cannot be sealed; replica appends are never refused. Recovery: raise
  `segment_disk_max_bytes` or add disk, or shorten retention. The warning becomes an `OperatorWarning` event
  with #1574.
