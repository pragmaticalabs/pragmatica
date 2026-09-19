### Fixed (2026-09-19 — #1231, #1232: concurrent publishers to one stream partition could share an offset, and WAL recovery renumbered records instead of honouring their stored offsets)
- **Partition append had no mutual exclusion.** Offset assignment was a lock-free `head + 1` in
  `OffHeapRingBuffer`, and several threads reach one partition (slice publishes, HTTP writes, forwarded
  publishes, replica receipt, durable-entity writes on different keys). Measured before the fix: 16
  threads × 5,000 publishes to one partition acked only 37,146 distinct offsets out of 80,000, so
  payloads overwrote each other and callers were told the same offset. Each partition now has one ordered
  append section (`OffHeapRingBuffer.appendOrdered`). It covers offset assignment, the WAL frame write
  and the replication send, so concurrent publishers get distinct contiguous offsets, the WAL file is in
  offset order, and replicas receive events in offset order. Only the group-commit fsync is awaited
  after the section is released, so concurrent publishers still share fsyncs. The ring's own `append`,
  `appendBatch`, `seedHead` and retention sweeps take the same lock, as defence in depth.
  `[mechanism: offset assignment, frame write and send share the ring's appendLock; pinned in one JVM by
  StreamPartitionManagerOrderedAppendTest and OffHeapRingBufferConcurrentAppendTest]`
  `[design intent — unverified: no multi-node run]`
- **`PartitionWal.append` wrote its frame on a pooled task, so file order was lock-acquisition order.**
  The frame is now written in the caller's thread (`write`); only the group commit (`commit`) is
  asynchronous. A write whose offset does not exceed the last written offset is refused with
  `WalError.OffsetRegression` before anything is written. A failed frame write now fail-stops the WAL,
  exactly as a failed fsync already did (#634-7). The ring had already assigned that record's offset, so
  a later frame that did land would leave a hole there; fail-stopping keeps the file a contiguous prefix,
  which a restart recovers.
  `[mechanism: the frame is written under writeLock before append returns; pinned by PartitionWalTest]`
- **WAL recovery ignored `record.offset()`.** It re-appended records in file order and let the ring
  number them, so a reordered frame swapped payloads between offsets, and a missing or duplicated frame
  shifted every later record relative to replicas, sealed segments and consumer cursors. Recovery now
  places records by their stored offsets. A file whose frames are only out of order recovers correctly.
  A gap or a duplicate refuses the partition with `StreamError.WalReplayMismatch`, logged at ERROR, and
  the stream is not materialized on that node. Records are never renumbered.
  **Operator action:** move the named `<wal-dir>/<stream>/<partition>.wal` aside and restart the node.
  With `replicas >= 2`, replica backfill restores the un-sealed tail; with `replicas = 1`, the un-sealed
  tail is lost.
  `[mechanism: records are sorted by stored offset and each must equal the ring's next offset; pinned by
  StreamPartitionManagerRecoveryTest]`
- `PartitionWalTest`'s concurrent-append case asserted an order-insensitive `containsAll`, so it accepted
  a reordered file as correct: the test encoded the defect. It is replaced by an order assertion.
