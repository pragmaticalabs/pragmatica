### Fixed (2026-10-04 — #752: StreamConsumerManager let its attached set diverge silently from the consumer runtime)
- **A declarative stream consumer the runtime lost stayed "attached" for good.** `attach` skips a key already held
  as attached, so a subscription the runtime no longer had was skipped on every later reconcile pass: that partition
  was consumed by nobody while the node reported it attached, and nothing was logged. Each reconcile pass now
  compares the attached set with the runtime's subscriptions. A key the runtime lost is reported and forgotten, so
  the same pass re-attaches it when it is still assigned here.
- **A detach (or abandon) whose runtime call failed was logged at DEBUG, or not at all.** It can only fail as
  `CONSUMER_NOT_FOUND`, meaning the runtime had already lost the subscription and no final cursor flush was made.
  Both cases now raise the operator warning `stream-consumer-state-diverged` (WARNING, subsystem `stream-consumer`,
  subject `group:stream[partition]`): a log line plus a cluster event, through the node's operator-warning sink.
  `[verified: aether/node/src/test/java/org/pragmatica/aether/node/stream/StreamConsumerManagerTest.java]`
  (`StateDivergence`, against the recording runtime double; wiring pinned by `OperatorWarningWiringTest`). Ordinary
  passes and an ordinary stop raise nothing (same class).
- `attach`'s early return for an already-attached key is now logged at DEBUG, so a pass's decisions can be
  reconstructed.
- `SubscriptionSnapshot.stalled` now states its meaning: a processing-failure latch under the STALL strategy, never
  a liveness signal. A consumer that receives nothing reads `stalled = false`.
- Item 2 of the ticket (a subscribe that never resolves wedges the key) no longer applies: `runtime.subscribe` now
  returns a synchronous `Result`.
- `[unverified: no multi-node run; how a runtime could lose a subscription in production is not known — the
  reconcile check detects it whatever the cause]`
