### Performance (2026-09-19 — #1245: publishBatch serialized every event through its own fsync and replication round-trip, and re-resolved each keyless event's partition)
- **An EVENTUAL `publishBatch` was not a storage batch.** Each event in a partition group was published
  on its own: its own blocking WAL fsync and its own replication message and ack round-trip. Measured
  before the fix: 100 same-partition events sent **100** `ReplicateEvents` messages. A local partition
  group is now appended in ONE ordered section (ring batch append, one WAL frame per event, ONE
  replication message), and the publisher awaits one group commit and one replication ack on the group's
  last offset. The same 100 events send one message. A remote-owner group is still forwarded event by
  event, in order, because no batch forward exists.
  `[mechanism: StreamWriteRouter routes local groups to StreamPartitionManager.publishLocalBatchAtFloor,
  which runs OffHeapRingBuffer.appendBatchOrdered; DefaultReplicationManager.replicateEvents sends
  the run in byte-budgeted chunks to each replica; pinned in one JVM by DefaultStreamPublisherBatchTest]`
- **Keyless batch events were published to partitions other than the ones they were grouped under.** The
  in-order chain resolved each event's partition a second time, advancing the round-robin counter again.
  Every partition could still receive the right *number* of events, but not the right events. Each group
  now publishes to the partition it was grouped by.
  `[mechanism: the group key is passed through and never re-resolved; pinned by DefaultStreamPublisherBatchTest,
  including an uneven-group case that catches a once-per-group re-resolution the ticket's 8-event case cannot]`
- **Oversized runs retain the sequential eviction path** (#1287 review). A run larger than the
  ring's data region is appended event by event inside one ordered section. If every append succeeds,
  the run is contiguous and awaits one WAL group commit. If the run is refused before appending because
  an event cannot fit the frozen allocation, the router falls back to individual publishes (#1233).
  Previously that case dropped the whole batch and acknowledged success. This does not promise
  per-event outcome precision after a partial local append: sealing refusal can leave a prefix in the
  ring while every input remains outcome-unknown, without automatic retry.
  `[mechanism: OffHeapRingBuffer.appendRunLocked plus RUN_DOES_NOT_FIT routing; pinned by
  DefaultStreamPublisherBatchTest's batch-versus-per-event comparisons]`
- **Replication chunks use a byte accounting budget** of half the cluster transport frame limit
  (`QuicClusterServer.MAX_FRAME_LENGTH`). Each event contributes its payload length plus a 32-byte
  allowance, so tiny events also consume the budget. The other half is chosen headroom for fixed
  fields/envelopes, not a measured serialized-size bound; one event above the budget is sent alone.
  The batch still awaits one cumulative acknowledgement on its last offset.
  `[mechanism: DefaultReplicationManager.chunkEnd; tests exercise payload splitting, not a complete
  codec-size or throughput bound]`
- **rc4 integration preserves per-input outcomes and current write authority.** Local runs use the shared
  owner router, committed-owner admission, live min-sync floor/barrier, and durable/visible frontier.
  A failed cumulative barrier reports every submitted event as outcome-unknown; it never labels an
  already-appended suffix not-attempted. Remote and oversized-run fallback chains retain their successful
  prefix and stop before later events after a failure. The batch is not atomic and ambiguous runs are not
  automatically retried.
  `[verified: BatchPublishOutcomeTest, StreamWritePathContractTest.StreamPublisherBatchPath]`
- **Review cleanup:** removed the unused `StreamPartitionManager.publishLocalBatch` wrapper and its
  private fallback chain; live batch callers retain the owner/floor-aware router. A no-WAL EVENTUAL
  regression pins `SEALING_BEHIND` after a partial ring append: every input remains outcome-unknown,
  the durable/visible frontier does not advance, no replication continuation runs and no retry occurs.
