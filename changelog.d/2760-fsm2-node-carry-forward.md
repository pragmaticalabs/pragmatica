### Fixed (2026-11-07 — #2760: FSM2 node carry-forward of the legacy topic and config fixes)
- **The FSM2 slice legs wrote no topic-publisher row and refused no topic tier conflict** (#1337, R-CF.12/13/14): the legacy
  topic pass writes a node-scoped `TopicPublisher` row with the delivery tier read from the publisher's own section, removes
  it on unload, and refuses activation when a durable subscriber meets an ephemeral publisher of another slice (in both
  orders). The FSM2 builders now do the same; a refusal fails the chain's legs step, which is retried, as under legacy.
  `[verified: aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/wiring/SliceLegBuildersGoldenTest.java]`
  (builder-level parity against the legacy builders, not a live-path test).
- **The FSM2 cluster mirror held no topic rows, so a departed node's topic rows stayed in the KV** (#1337, R-CF.15): the
  mirror now indexes `TopicSubscriptionKey` and `TopicPublisherKey`, the departure batch removes the departed node's rows
  at their committed values, and the reconcile sweep removes a row of a departed node that commits late, as legacy's
  node-removal batch and stale sweep do.
  `[verified: aether/node/src/test/java/org/pragmatica/aether/deployment/cluster/fsm2/Rc5ParityTripwireTest.java]`
  (in-JVM FSM harness).
- **A routed slice's `[json]` settings section was not registered for config updates under FSM2** (#2394 carried
  forward, R-CF.22/29): a committed `json.*` change now reaches the slice factory's `notifyConfigUpdate` with section `json`.
  `[verified: aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/wiring/ConfigCarryForwardChainTest.java]`
- Pins for behaviour FSM2 already had: a late ACTIVE batch of a timed-out chain is refused by the version fence (NT-145), an
  exhausted or consensus-refused LOADED write is retryable rather than fatal (NT-153), and config sections are registered
  again on reactivation, the config watch survives deactivate/suspend, and a failed load is retried (NT-154, NT-161, NT-162).
