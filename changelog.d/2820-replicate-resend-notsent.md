### Fixed (2026-11-07 — #2820: a publish to a `confirmation_factor` 2 stream could fail with "Replication acknowledgment timed out" after one refused replicate)
- **The owner discarded the outcome of every replicate send.** Replication rides the FORWARD lane, which refuses a write at
  once under backpressure, and `DefaultReplicationManager.sendChunk` dropped the send's `WriteOutcome`. One transient
  refusal lost the replicate silently: the replica never received it, and the owner waited its full 5 s ack bound for an
  ack that could not come, so the publish failed. The owner now reads each send's outcome. When it proves the frame never
  left the node (`BackpressureRefused`, `NoPeerState`, `EncodeFailed`, now classified once as
  `WriteOutcome.provablyNotSent()`), the same chunk (same `fromOffset` and owner epoch) is re-sent to that replica after a
  backoff of 20 ms, doubled per attempt up to 500 ms. Re-sends stop before the 5 s ack deadline of the waiting publish.
  `[mechanism: re-send only on a provably-not-sent outcome; unit-pinned by ReplicationResendNotSentTest]`
- **A frame that was sent, or whose fate is unknown, is never re-sent by this path** (`Sent`, `ConnectionDead`, a failed
  send promise), so a replica cannot receive a chunk twice through it. A missed chunk that did leave is repaired by
  catch-up, as before. The FORWARD lane's fast-fail is unchanged.
  `[mechanism: WriteOutcome.provablyNotSent() is false for Sent and ConnectionDead; unit-pinned by ReplicationResendNotSentTest]`
- End to end, a publish to a CF2 stream is acknowledged when the owner's replicate of it to an in-sync replica is refused
  once by the transport. `[verified: aether/ember/src/test/java/org/pragmatica/aether/ember/EmberReplicateRefusedResendTest.java]`
- Operator recovery: none needed for a transient refusal. A replica that stays unwritable until the deadline is named in one
  WARN (`Replicate of <stream>[<partition>]@<offset> to replica <id> on lane FORWARD not written before the ack deadline`),
  and that publish fails with the replication ack timeout, as before; the client may retry it.
