### Fixed (2026-09-14 — #771: a slice's ACTIVE and its endpoint publication were two puts, and dependents activated between them)
- **The node wrote `ACTIVE` to its `NodeArtifactKey` first and published its endpoints (the same
  key, with `methods`) in a later put.** The leader activates a slice's dependents on the first
  `ACTIVE` it observes for that key (`ClusterDeploymentState.trackSliceState` → `handleSliceActive`
  → `activateDependentSlices`), and every node's `EndpointRegistry` learns the endpoints from the
  same key's `methods` — so a dependent could be told to activate before the endpoint it invokes at
  activation (`SliceInvoker.verifyEndpointExists`) existed anywhere. The ROUTING-ack fast path and
  the stuck-ACTIVATING/ROUTING remediations wrote `ACTIVE` with no endpoint put behind them at all.
  #771's earlier mitigation (`b983376e4`) typed the miss as `Intermittent` so the deployment retried
  instead of collapsing; the order itself was open.
  [mechanism: `NodeDeploymentState.performActivation` chained `transitionToActiveWithStreamRefs`
  before `publishEndpoints`; `fastTransitionToActive`/`remediateStuck*` call `transitionTo(ACTIVE)`]
- Every `ACTIVE` write now carries the slice's endpoints in the same put: `nodeArtifactValueFor`
  builds the value for both state-update paths, and for `ACTIVE` it is `activeNodeArtifactValue`
  from the loaded slice's methods. The readiness the leader acts on IS the publication — one put,
  one event, in every path that reaches `ACTIVE`. The activation chain's trailing `publishEndpoints`
  step is gone (it would re-put identical content); the reactivation-after-suspend path keeps it,
  because that path performs no `ACTIVE` transition of its own — an HTTP-routed slice re-enters
  through ROUTING (`publishRoutesIfPresent`), whose put carries no methods, and any ack-driven
  ACTIVE that follows carries them again.
  [verified: `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/node/fsm/NodeDeploymentStateEndpointsBeforeActiveTest.java`
  — drives the real code through the FSM harness with a one-method slice and records every command
  the node submits; `firstActivePutSeenByTheCluster_carriesTheEndpoints` pins the activation chain's
  writer (`updateSliceStateWithExtraCommandsAndRetry`), and
  `forcedActivatingToActive_viaUpdateSliceStateWithRetry_carriesTheEndpoints` pins the
  `transitionTo(ACTIVE)` writer (`updateSliceStateWithRetry`) that the ROUTING-ack fast path and
  both stuck remediations use; each goes red on its own writer alone, and the first `ACTIVE` put
  must carry `["execute"]` in both]
- A slice with no methods, or no longer in the store at transition time, writes the plain `ACTIVE`
  value as before; `EndpointRegistry` ignores empty-method puts, so such a put never erases
  endpoints an earlier put published. [mechanism: `EndpointRegistry.registerEndpointsFromNodeArtifact`
  returns on empty `methods`]
- Residual, cited not fixed: a leader-issued ACTIVATE can still be applied on a follower before that
  follower's KV apply has reached the endpoint-bearing put — #1109. `WorkerDeploymentManager` keeps
  its own state-then-endpoints order; it is unwired (#1125).
