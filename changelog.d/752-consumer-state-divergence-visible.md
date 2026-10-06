### Fixed (2026-10-04 — #752: StreamConsumerManager let its attached set diverge silently from the consumer runtime)
- **A declarative stream consumer the runtime lost stayed "attached" for good.** `attach` skips a key already held
  as attached, so a subscription the runtime no longer had was skipped on every later reconcile pass: that partition
  was consumed by nobody while the node reported it attached, and nothing was logged. Each reconcile pass now
  compares the attached set with the runtime's subscriptions. A key the runtime lost is reported and forgotten, so
  the same pass re-attaches it when it is still assigned here.
- **A detach (or abandon) whose runtime call failed was logged at DEBUG, or not at all.** It can only fail as
  `CONSUMER_NOT_FOUND`, meaning the runtime had already lost the subscription and no final cursor flush was made.
  The detach case now raises the operator warning `stream-consumer-detach-found-nothing` (WARNING, subsystem
  `stream-consumer`, subject `group:stream[partition]`, a point event with the state already reconciled): a log line
  plus a cluster event, through the node's operator-warning sink. The pass-found case raises
  `stream-consumer-state-diverged` (below the same way).
  `[verified: aether/node/src/test/java/org/pragmatica/aether/node/stream/StreamConsumerManagerTest.java]`
  (`StateDivergence`, against the recording runtime double; wiring pinned by `OperatorWarningWiringTest`). Ordinary
  passes and an ordinary stop raise nothing (same class).
- **The divergence has a recovery event.** A divergence found by a reconcile pass is followed by one operator warning
  `stream-consumer-state-repaired` (same subject, INFO) once the consumer is attached again, or once the pass no
  longer wants it on this node. A detach-found divergence has its own code and no recovery. `[verified: same test class, StateDivergence]`
- **`WarningLevel.INFO`** is a new generic level of the operator-warning mechanism: logs at INFO, publishes at
  `ClusterEvent.Severity.INFO`. A code can name the code it is the recovery of; the aggregator publishes such a
  recovery only after an event of the code it closes is in the log for the same subject (held while that event is
  redelivered, dropped with it), once per event, outside the 60 s window; a recovery also ends the closed code's
  throttle window, so a recurrence is shown. `[verified: ClusterEventAggregatorTest onOperatorWarning_recovery*, OperatorWarningsTest, OperatorWarningCodeTest]`
- **The quorum-loss abandon could raise the divergence warning falsely.** `abandonAll` did not take the pass lock, so
  it could run while a reconcile pass was between recording a subscription as attached and subscribing it in the
  runtime: the abandon found nothing to remove and reported a divergence that did not exist, and the pass then
  subscribed behind it, leaving a subscription the manager no longer tracked. `abandonAll` now waits for a pass in
  flight, like `stop`. `[verified: same test class, AbandonDuringAttach]`
- `attach`'s early return for an already-attached key is now logged at DEBUG, so a pass's decisions can be
  reconstructed.
- `SubscriptionSnapshot.stalled` now states its meaning: a processing-failure latch under the STALL strategy, never
  a liveness signal. A consumer that receives nothing reads `stalled = false`.
- Item 2 of the ticket (a subscribe that never resolves wedges the key) no longer applies: `runtime.subscribe` now
  returns a synchronous `Result`.
- `[unverified: no multi-node run; how a runtime could lose a subscription in production is not known — the
  reconcile check detects it whatever the cause]`
