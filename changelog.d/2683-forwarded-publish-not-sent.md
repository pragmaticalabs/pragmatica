### Fixed (2026-11-06 — #2683: a publish forwarded to the partition owner could answer 503 "outcome unknown" for a frame that was never sent)
- **A forwarded publish whose frame the transport refused before writing it was invisible to its caller.** The STREAMING
  governor forwards a publish to the partition's owner over the FORWARD lane. When that lane was momentarily unwritable
  (CI run 42030156322: unwritable for 21 ms, one frame refused within 20 ms of the publish entering), the refused frame was
  dropped and the publish waited for an ack that could not come, until the entry node's HTTP forward hop expired and the
  client got 503 "outcome unknown" although nothing had been written. Forwarded publishes now read the transport's send
  outcome, as forward reads do since #2574: a frame refused under backpressure or with no peer state fails the forward at
  once with the transient `StreamForwardError.PublishNotSent`, which the write router retries (the bounded retry for a
  pre-append refusal: up to 3 attempts, 150 ms apart, inside the caller's budget); a frame that could not be encoded fails
  with the terminal `PublishNotEncoded`. A publish whose every retry is refused answers 503 "refused before writing, retry".
  A dead connection is unchanged: the offline buffer may still deliver that frame, so its outcome stays unknown.
  `[mechanism: StreamForwardClient.publishRemote fails the pending publish in the transport's outcome callback; unit-pinned by StreamForwardRefusedSendTest, DefaultStreamPublisherForwardRetryTest, StreamApiRoutesPublishPartitionTest]`
  `[verified: aether/ember/src/test/java/org/pragmatica/aether/ember/EmberForwardedPublishRefusedTest.java]`
- Operator recovery: none needed. A persistently refusing lane is visible as `Backpressure on peer ... lane FORWARD`
  warnings and the `quic_backpressure_drops_total` counter; the client retries a "refused before writing" 503 safely.
- Not changed: a forwarded control-log append (`appendControlRemote`) still ignores the send outcome.
