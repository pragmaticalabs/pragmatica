### Fixed (2026-09-19 — #1259: awaitReplication lost an ack that landed between its registry snapshot and waiter registration)
- **A replicated write could be reported as timed out.** `DefaultReplicationManager.awaitReplication`
  sampled the registry for peers that had already acked, then registered the waiter. An ack landing
  between those two steps found no waiter, and the stale sample did not contain it, so the await ran to
  the 5 s `REPLICATION_TIMEOUT` (with #1236: `PublishOutcomeUnknown`) although replication succeeded. It
  hit the last write of a burst, which is exactly the one a caller waits on.
- The waiter is now registered **before** the registry snapshot seeds it. Every ack is therefore seen
  either by the waiter or by the snapshot, and one conditional-remove completion path resolves the
  promise exactly once across the ack, the post-registration reconcile and the timeout.
  [mechanism: `registerThenReconcile` puts before `peersAtOrAbove`; `complete` resolves only on
  `remove(key, pending)`; pinned by `AwaitReplicationRaceTest`, which forces the interleaving through a
  package-private seam]
- Two awaits on the same `(stream, partition, offset)` no longer overwrite each other. Each await is
  its own entry, so both resolve.
  [unverified: exercised in-JVM with a forced interleaving, not observed under multi-node load]
