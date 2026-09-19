### Fixed (2026-09-19 — #1230: stream replica nodes accepted application writes)
- **A node holding a replica of a stream partition appended application writes locally**, assigning
  offsets independently of the owner, so the owner and a replica could give the same offset to
  different events. Both write routers chose the local path because a partition ring existed on the
  node, and replicas build that ring too. The epoch fence could not catch it: a live replica stamps the
  same committed epoch the owner does. Exposed paths: durable topics (always RF ≥ 2), app streams with
  `replicas >= 2`, durable-topic DLQ publishers, and `system:cluster-events`, which every node holds a
  replica of.
- `DefaultStreamPublisher` and `StreamWriteRouter` now route by the resolved HRW owner, the rule
  `PartitionedStreamAccess` already applied. A remote owner is write-forwarded even when a replica ring
  is present locally.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/OwnerAuthorizedWritesTest.java`, unit level]
- `StreamPartitionManager.publishLocal` refuses with the transient `StreamError.NotOwnerAppend` when
  the committed `StreamPartitionOwnershipValue` names another node (`OwnerWriteAdmission`, bound in
  `AetherNode` to the #568 liveness-filtered committed-owner source). `appendRecovered` (replication
  receipt and backfill) is exempt. When no ownership record is committed yet, the append is admitted,
  unchanged from before.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/OwnerAuthorizedWritesTest.java`, unit level]
- Ownership-lag window (HRW has moved, the leader has not yet committed): a forwarded publish refused
  this way is answered retryable, so the existing bounded forward retry (#485) handles it. A refused
  owner-local append is redirected to the committed owner, which is still the fenced single writer.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/forward/StreamForwardHandlerTest.java`]
  [unverified: whether the 3×150ms retry budget covers the reconcile→commit latency of a real multi-node reshuffle]
- `ReplicationReceiveHandler` refuses (does not apply, does not ack) a batch whose sender cannot be the
  committed owner at the batch's epoch. This also stops the "stale duplicate" re-ack that let a
  non-owner's min-sync barrier count a replica holding a different event at that offset. A batch at a
  newer epoch than the replica's committed view is accepted, because the replica cannot judge it, and
  the owner-handoff flow depends on that.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/replication/ReplicationSenderValidationTest.java`]
- The `system:cluster-events` publisher now has a forward client and the HRW owner resolver, so
  `emitAsLeader` and `emitLocal` on a non-owner forward to the owner instead of appending to the local
  replica ring.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/SystemStreamFactoriesTest.java`, factory level; the `AetherNode` wiring is not exercised by a multi-node test]
