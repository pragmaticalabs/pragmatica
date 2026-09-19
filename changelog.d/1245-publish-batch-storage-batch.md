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
