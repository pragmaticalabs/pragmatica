### Performance (2026-09-19 — #1245: publishBatch serialized every event through its own fsync and replication round-trip, and re-resolved each keyless event's partition)
- **An EVENTUAL `publishBatch` was not a storage batch.** Each event in a partition group was published
  on its own: its own blocking WAL fsync and its own replication message and ack round-trip. Measured
  before the fix: 100 same-partition events sent **100** `ReplicateEvents` messages. A local partition
  group is now appended in ONE ordered section (ring batch append, one WAL frame per event, ONE
  replication message), and the publisher awaits one group commit and one replication ack on the group's
  last offset. The same 100 events send one message. A remote-owner group is still forwarded event by
  event, in order, because no batch forward exists.
  `[mechanism: StreamPartitionManager.publishLocalBatch runs OffHeapRingBuffer.appendBatchOrdered, and
  DefaultReplicationManager.replicateEvents sends one message per replica; pinned in one JVM by
  DefaultStreamPublisherBatchTest]`
- **Keyless batch events were published to partitions other than the ones they were grouped under.** The
  in-order chain resolved each event's partition a second time, advancing the round-robin counter again.
  Every partition could still receive the right *number* of events, but not the right events. Each group
  now publishes to the partition it was grouped by.
  `[mechanism: the group key is passed through and never re-resolved; pinned by DefaultStreamPublisherBatchTest,
  including an uneven-group case that catches a once-per-group re-resolution the ticket's 8-event case cannot]`
- **A batch is never worse than the per-event publishes it replaced** (#1287 review). A run larger than
  the ring can hold at once, up to beyond the whole data region, is appended event by event inside the
  same ordered section, each event evicting as a sequential append would; it remains one contiguous run
  with one WAL commit. A run containing an event the frozen ring can never hold is published event by
  event through the single-publish path, so that event gets exactly a single publish's outcome (#1233).
  Previously such a batch was dropped whole and acked as success.
  `[mechanism: OffHeapRingBuffer.appendRunLocked plus RUN_DOES_NOT_FIT routing; pinned by
  DefaultStreamPublisherBatchTest's batch-versus-per-event comparisons]`
- **Replication messages are split by bytes** below the cluster transport's frame limit
  (`QuicClusterServer.MAX_FRAME_LENGTH`, now declared once for server and client). Each chunk's encoded
  size (payload plus a bounded per-event framing cost, so millions of tiny events split too) is at most half
  of it; the batch still awaits one ack on its last offset.
  `[mechanism: DefaultReplicationManager.chunkEnd; pinned by DefaultStreamPublisherBatchTest]`
- **rc4 integration preserves per-input outcomes and current write authority.** Local runs use the shared
  owner router, committed-owner admission, live min-sync floor/barrier, and durable/visible frontier.
  A failed cumulative barrier reports every submitted event as outcome-unknown; it never labels an
  already-appended suffix not-attempted. Remote and oversized-run fallback chains retain their successful
  prefix and stop before later events after a failure. The batch is not atomic and ambiguous runs are not
  automatically retried.
  `[verified: BatchPublishOutcomeTest, StreamWritePathContractTest.StreamPublisherBatchPath]`
