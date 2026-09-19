### Fixed (2026-09-19 — #1236: a publish reported as failed could already be in the log)
- **`NOT_ENOUGH_REPLICAS` was detected after the local append.** Every owner-side publish path appended
  to the ring, fsynced the WAL and fired replication before `awaitReplication` found the replica set
  could not meet `min-sync`, so a publish reported as failed was readable by local consumers and
  possibly on peers. Durable topics require `min-sync == replicas`, so one unavailable replica made
  this routine for every publish on its partitions.
- The replica floor is now checked **before** the append — `ReplicationManager.ensureReplicaFloor`,
  called ahead of `publishLocal` by `DefaultStreamPublisher`, `PartitionedStreamAccess`,
  `StreamWriteRouter` and the owner side of a forwarded publish (`StreamForwardHandler`). A
  `NOT_ENOUGH_REPLICAS` refusal now leaves the ring and the WAL empty.
  [mechanism: the check precedes `StreamPartitionManager.publishLocal` in each of the four paths;
  pinned by `HonestPublishOutcomeTest`, which reads the ring head offset and the WAL through an
  independent reader]
- **New cause `PublishOutcomeUnknown` (`slice-api`).** A barrier failure after the append — the peer-ack
  timeout, or a replica set that shrank between the check and the await — is no longer reported as a
  failure: the event may be in the log. The owner carries this across the forward wire
  (`PublishForwardResponse.outcomeUnknown`), and a publish-forward timeout on the sender is reported the
  same way, because the owner may have appended before its response was lost.
  [mechanism: `.mapError(PublishOutcomeUnknown.FACTORY)` on every post-append await; `StreamForwardClient`
  rebuilds the cause from the wire flag and wraps `FORWARD_TIMEOUT`]
- `PublishOutcomeUnknown` is deliberately **neither transient nor terminal**: a retry facility that
  re-runs the whole operation would mint a fresh message ID and write a duplicate that dedup cannot
  collapse. Retry only with the same message ID (#1237).
- Contract text now states three outcomes — success (in the log), failure (not in the log),
  outcome-unknown (may be in the log) — in `DurableTopicPublisher`, `durable-pubsub-spec.md` §5 and
  `guarantees.md`. **Breaking** (pre-GA): callers that matched `REPLICATION_TIMEOUT` or
  `FORWARD_TIMEOUT` on the publish path now receive them as `PublishOutcomeUnknown.origin()`.
- Not changed: a WAL fsync failure after the ring append still reports a failure for an event visible
  in the ring (#1235 family). [unverified: no multi-node run exercised the floor refusal or the
  outcome-unknown path; the evidence is in-JVM]
