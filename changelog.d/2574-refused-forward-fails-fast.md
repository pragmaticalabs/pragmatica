### Fixed (2026-11-02 — #2574: the first publish to a new `confirmation_factor` 2 stream could answer 503 "outcome unknown")
- **A forward request whose frame the transport refused was invisible to its caller.** A stream forward read or publish
  that the FORWARD lane refused under backpressure was dropped, and the caller waited out its timeout (2 s for a read)
  for an answer that could not come. A new replica's first catch-up read hit exactly this at stream creation; the offset-0
  gap trigger then joined that dead backfill and pulled nothing, so the owner's wait for every in-sync replica outlived the
  entry node's forward hop and the client got 503 "outcome unknown". A refused forward now fails at once with the
  transient `StreamForwardError.ForwardNotSent`. Only a refusal before write counts (`BackpressureRefused`, `NoPeerState`,
  `EncodeFailed`); a frame reported `Sent` or `ConnectionDead` may still be delivered from the offline buffer and keeps
  waiting. `[mechanism: the client fails the request in the transport's outcome callback; unit-pinned by StreamForwardRefusedSendTest]`
- **A forwarded publish refused before write now answers the retryable 503 ("refused before writing, retry")**, not
  "outcome unknown": it never reached the owner, so nothing was appended.
  `[mechanism: ForwardNotSent is on the pre-append allow-list; unit-pinned by StreamApiRoutesPublishPartitionTest]`
- **A backfill call that joins a flight which then fails re-runs the backfill once** (bounded: a re-run that fails does
  not re-run again). The re-run is a new flight started after the failed one left the slot, so the single-flight
  properties of #1638 hold. A joined flight that succeeds is not re-run.
  `[mechanism: per-flight join state, re-run on release; unit-pinned by PartitionBackfillJoinedRerunTest]`
- End to end, the first publish to a new CF2 stream is acknowledged when every replica's first catch-up read is refused
  by the transport. `[verified: aether/ember/src/test/java/org/pragmatica/aether/ember/EmberFirstPublishRefusedCatchupTest.java]`
- **Failing fast does not raise the FORWARD send rate.** `ForwardNotSent` carries the wait the request would have spent
  on its answer, and every caller that re-sends on its own trigger keeps at least that long before it re-sends. A refused
  backfill flight holds its single-flight slot for the wait, and a trigger meanwhile re-runs it once at the end. A consumer
  whose forwarded read is refused parks for the wait. The owner-activation gate runs no probe round inside it. The
  dead-letter publish and the cluster-event redelivery retry no sooner than it. Under a lane that keeps refusing, each of
  these sends no more often than the old timeout-paced wait did.
  `[mechanism: each caller waits the cause's retryAfter; unit-pinned per caller class by ForwardRefusalPressureTest, which compares counted sends with the timeout-paced arm]`
  Every other caller was already paced, or retries once, immediately, on a different node.
  `[mechanism: caller table in the #2574 PR]`
- Operator recovery: none needed; a refused forward is retried by the caller's schedule. A persistently refusing lane is
  visible as `Backpressure on peer ... lane FORWARD` warnings and the `quic_backpressure_drops_total` counter.
