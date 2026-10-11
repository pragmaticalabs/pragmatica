### Fixed (2026-10-11 — #2087: stream replicas served records above the acknowledged high-water)
- **A consumer could read a record that a failover then replaced at the same offset.** A replica's visible position was its own
  log end, so it served offsets the owner had not seen acknowledged (a verifier probe: acknowledged through offset 7, replica
  served 8-9). Streams are now read-committed: a record is added only when its configured acknowledgements arrive, and no
  consumer read path of the owner or of a replica returns an offset above the acknowledged high-water. The owner sends its
  visible position to its replicas (`ReplicationMessage.CommitAdvance`, new wire type, tag 2142) when it advances and repeats it
  every second; a replica serves `min(own durable, the owner's reported position)`, and nothing until the owner has reported.
  Reads above the position return an empty page, not an error. A promoted replica keeps the position it held and exposes its
  tail once its own peers acknowledge it. Replication-class reads (catch-up, survivor pulls) and the entity-log fold still read
  the appended head. The staleness bound after the last acknowledgement is one tick (1 s) plus delivery.
  `[mechanism: StreamPartitionManager.exposeReplicaCommitted bounds every replica visible advance by the owner's reported position]`
  `[verified: aether/ember/src/test/java/org/pragmatica/aether/ember/EmberReadCommittedFailoverTest.java]`
  Per-path table: `aether/docs/reference/guarantees.md`.
- **Metadata and bounds report the committed span.** `StreamAccess.metadata()`, the partition-detail and consumers routes and the stream totals
  reported the appended head and the ring's raw count (head 1, count 2 against a visible head of -1). They now report the committed head and
  the committed count; the replication code that needs what a node HOLDS reads `appendedHead`.
  `[verified: StreamRoutesCommittedBoundsTest, ReadCommittedStreamTest]`
- **Durable-entity reads serve committed state only.** A client `get` of a key whose newest record is not yet committed waits up to the replication
  barrier timeout and then fails `NotYetCommitted` (transient); the owner's own writes and timers still sequence against pending entries, and a
  checkpoint claims only committed offsets. `[verified: EntityCommittedReadEndToEndTest]`
- **An unacknowledged tail commits without a new publish.** The owner re-offers its tail as an ordinary batch after five quiet ticks (at most 256
  events); replicas verify what they hold and acknowledge it. `[verified: EmberReadCommittedFailoverTest]`
