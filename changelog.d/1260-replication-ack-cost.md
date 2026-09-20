### Performance (2026-09-19 — #1260: every replica ack scanned all pending writes, and ack timers were never cancelled)
- **Each ack walked every pending synchronous publish on the owner**, across all streams and
  partitions: O(pending) per ack. Pending awaits are now indexed per partition and ordered by offset,
  so an ack visits only its own partition's awaits at or below the confirmed offset.
  [mechanism: `ConcurrentSkipListMap.headMap` per `PartitionKey`; pinned by
  `AwaitReplicationRaceTest.handleAck_visitsNoWaiter_whenAllWaitersAreOnAnotherPartition` — 0 visits
  where the old scan made 10,000]
- **A completed await left its 5 s timeout queued.** The timer is now cancelled on completion.
  `cancel` only marks the scheduler entry, so the queue slot itself is released when it comes due; a
  shared expiry wheel would be the next step if that matters.
  [mechanism: `complete` cancels the stored `ScheduledFuture`; pinned by
  `awaitReplication_leavesNoLiveTimer_afterSuccessfulAck`]
