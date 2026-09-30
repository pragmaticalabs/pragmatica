### Fixed (2026-09-28 — #1577: a replication batch was stamped with the latest epoch of its events)
- **A batch accumulated across an ownership change carried the new owner's epoch for the old owner's events.**
  A batch now never spans two epochs `[mechanism: an event of a new epoch flushes the open batch, on the same
  thread, before it is batched]`, so a batch's stamp is the epoch of every event in it. Pinned by
  `ReplicationBatcherTest$EpochBoundary`.
