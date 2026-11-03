### Fixed (2026-11-02 — #2574: the first publish to a new `confirmation_factor` 2 stream could answer 503 "outcome unknown")
- **A forward read whose frame the transport did not send was invisible to its caller.** A stream forward read refused by
  a backpressured FORWARD lane (or sent to a dead connection, or a peer with no state) was dropped, and the read waited
  out its 2 s timeout for an answer that could not come. A new replica's first catch-up read hit exactly this at stream
  creation; the offset-0 gap trigger then joined that dead backfill and pulled nothing, so the owner's wait for every
  in-sync replica outlived the entry node's forward hop and the client got 503 "outcome unknown". Forward reads now use
  the network's outcome-reporting send (`ClusterNetwork.sendOutcome`, the same dispatch on the same lane): a read that was
  not sent fails at once with the transient `StreamForwardError.ForwardNotSent`, and one whose message could not be encoded
  with the terminal `ForwardNotEncoded`. Forwarded publishes are unchanged (a follow-up).
  `[mechanism: the client fails the read in the transport's outcome callback; unit-pinned by StreamForwardRefusedSendTest]`
- **Failing fast does not raise the sustained FORWARD send rate.** Each caller whose next read is driven by its own trigger
  holds after a refusal, by a doubling hold of 250 ms to a 4 s cap with ±20% jitter, reset once a read is sent: the backfill
  per partition (a refused flight keeps its single-flight slot for the hold), the owner-activation gate per probed peer, the
  consumer per (group, partition). The cap keeps a held key's wait after the lane recovers under the 5 s CF2 ack timeout
  (at most 4.8 s with jitter). Measured against the old timeout-paced rate at the production 2 s read timeout: the first
  10 s is about 1.2× it, a sustained 60 s window about 0.6×.
  `[mechanism: RefusalBackoff at each pacing site; unit-pinned by RefusalBackoffTest, RefusalBackoffWiringTest and ForwardRefusalPressureTest]`
- **A backfill flight that a call joined after it began re-runs once when it lands, failed or succeeded** (an empty success
  included, where the read reached the owner before offset 0 existed). A landing with no joiner never re-runs, so re-runs
  chain only while real triggers keep joining. The re-run is a new flight started after the landed one left the slot, so
  the single-flight properties of #1638 hold.
  `[mechanism: per-flight join state, re-run on release; unit-pinned by PartitionBackfillJoinedRerunTest]`
- End to end, the first publish to a new CF2 stream is acknowledged when every replica's first catch-up read is refused
  by the transport. `[verified: aether/ember/src/test/java/org/pragmatica/aether/ember/EmberFirstPublishRefusedCatchupTest.java]`
- Operator recovery: none needed; a refused read is retried on the caller's hold. A persistently refusing lane is visible as
  `Backpressure on peer ... lane FORWARD` warnings and the `quic_backpressure_drops_total` counter.
