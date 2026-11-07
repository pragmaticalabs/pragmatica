### Fixed (2026-11-07 — #2760: FSM2 node carry-forward of the legacy topic and config fixes)
- **The FSM2 slice legs wrote no topic-publisher row and refused no topic tier conflict** (#1337, R-CF.12/13/14): the legacy
  topic pass writes a node-scoped `TopicPublisher` row with the delivery tier read from the publisher's own section, removes
  it on unload, and refuses activation when a durable subscriber meets an ephemeral publisher of another slice (in both
  orders). The FSM2 builders now do the same; a refusal fails the chain's legs step, which is retried, as under legacy.
  `[verified: aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/wiring/SliceLegBuildersGoldenTest.java]`
  (builder-level parity against the legacy builders, not a live-path test).
- **The FSM2 cluster mirror held no topic rows, so a departed node's topic rows stayed in the KV** (#1337, R-CF.15): the
  mirror now indexes `TopicSubscriptionKey` and `TopicPublisherKey`; the departure batch, the departed-row cleanup and the
  departure record's removal remove the node's rows at their committed values (an inferred departure removes them only
  once its record commits); and every reconcile pass on the leader removes a committed topic row whose node is neither a
  counted core nor a WORKER-directive node and is neither departing nor departed, at its committed value, as legacy's
  node-removal batch and stale sweep do. Nothing is swept while the core membership is unwired.
  `[verified: aether/node/src/test/java/org/pragmatica/aether/deployment/cluster/fsm2/Rc5ParityTripwireTest.java,
  aether/node/src/test/java/org/pragmatica/aether/deployment/cluster/fsm2/TopicRowRemovalTest.java]`
  (in-JVM FSM harness, including a row left by a removal batch lost across a leader change).
- **A config change applied before the FSM2 system attached was dropped silently** (#2757): it is now counted as
  `aether_deployment_fsm2_dropped_total{point="B5.ConfigApplied.preAttach"}`. The drop loses no value
  `[mechanism: no slice is registered for config before attach; registration reads the current value]`.
  `[verified: aether/node/src/test/java/org/pragmatica/aether/node/deployment/Fsm2SystemTest.java]` (the count).
- **A routed slice's `[json]` settings section was not registered for config updates under FSM2** (#2394 carried
  forward, R-CF.22/29): a committed `json.*` change now reaches the slice factory's `notifyConfigUpdate` with section `json`.
  `[verified: aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/wiring/ConfigCarryForwardChainTest.java]`
- Pins for behaviour FSM2 already had: a late ACTIVE batch of a timed-out chain is refused by the version fence (NT-145), an
  exhausted or consensus-refused LOADED write is retryable rather than fatal (NT-153), and config sections are registered
  again on reactivation, the config watch survives deactivate/suspend, and a failed load is retried (NT-154, NT-161, NT-162).
