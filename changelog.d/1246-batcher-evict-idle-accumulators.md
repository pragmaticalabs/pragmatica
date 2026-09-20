### Fixed (2026-09-19 — #1246: ReplicationBatcher scans every partition accumulator ever created every 1 ms and never evicts them)
- **`ReplicationBatcher` ran `flushAll` on a 1 ms fixed-rate timer that locked every accumulator ever
  created, including idle ones, and never removed any.** The cost was O(partitions ever owned) lock
  acquisitions per millisecond, growing for the life of the node. The batcher is not yet wired in
  production (#263), so no running node paid this cost; it would have started the moment #263 landed.
- The periodic scan is gone. The first event of a batch schedules a one-shot flush for that batch, and
  the first drain retires the accumulator and evicts it from the map, so an accumulator exists only
  while its partition has a pending batch. Idle or released partitions hold no map entry and cost no
  lock acquisition. `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/replication/ReplicationBatcherTest.java — AccumulatorEviction, 10k partitions, unit level]`
- An `add` that races a drain sees the accumulator retired and retries on a fresh one, and a batch's
  late one-shot on an already-drained accumulator sends nothing. `[verified: same file,
  add_concurrentWithSizeAndTimerFlushes_losesNoEvent — 4 threads × 5,000 events, exact delivery count]`
- The flush-latency bound is unchanged: every event is flushed within `maxDelay` of its `add`. Mean
  latency for a sparse single event rises from about `maxDelay/2` to `maxDelay`, because the flush now
  runs `maxDelay` after the batch's first event rather than on the next global tick.
  `[mechanism: one-shot scheduled at maxDelay on the batch's first event]`
