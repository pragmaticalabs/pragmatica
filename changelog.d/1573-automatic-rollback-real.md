### Fixed (2026-09-28 — #1573: automatic rollback, the SliceFailure event and its alert could never fire)
- **The all-instances-failed signal had no producer and no consumer path.**
  - `SliceInvoker`'s failure listener was never wired.
  - Its only producer, the failover path, is reached only by `invokeWithRetry`, which has no production caller.
  - `AlertManager.onAllInstancesFailed` was never routed.
  - The SliceFailure event and the alert therefore never fired, and neither did automatic rollback.
- **A leader-side detector is now the one producer.**
  - Each node counts slice executions at the admission boundary and ships the counts on its cluster-sync pong. It counts successes and *defects*. A defect is a failure the slice bridge produced: the method threw, a codec failed, or the method is missing.
  - A failure the slice method returns itself is never counted, and neither is an execution timeout.
  - The leader takes per-window deltas of the counts. A new producer incarnation or a counter drop starts a fresh baseline.
  - It declares a version failing when every ACTIVE instance's node shows at least 3 defects and no success within 30 s, with fresh metrics.
  - The event is routed to RollbackManager, to the SliceFailure cluster event, and (newly) to the AlertManager alert. The listener path in `SliceInvoker` is removed.
  [verified: `aether/aether-control/src/test/java/org/pragmatica/aether/controller/AllInstancesFailedDetectorTest.java` (deltas, counter reset, stale node, latch), `aether/aether-invoke/src/test/java/org/pragmatica/aether/invoke/AdmittedSliceBridgeOutcomeTest.java`, `aether/slice/src/test/java/org/pragmatica/aether/slice/DefaultSliceBridgeDefectTest.java`]
- **Rollback safety.**
  - A rollback happens only within a bake window (`RollbackConfig.bakeWindow`, default 15 min) after the version became the target. Outside it the event and the alert fire, but nothing is rolled back.
  - It never rolls back while a managed deployment owns the artifact.
  - It never rolls back to a version that already failed.
  - It never acts on an event for a version that is no longer the target.
  - `PreviousVersionValue` now carries the rollback count, the last rollback time and the failed versions. They are committed in the same batch as, and ahead of, the rollback's SliceTarget put, so cooldown and maxRollbacks hold across a leader change.
  - This is a KV codec change to `PreviousVersionValue` (wire SHAPE pin updated; tag unchanged).
  [verified: `aether/aether-control/src/test/java/org/pragmatica/aether/controller/RollbackManagerAutoRollbackSafetyTest.java`]
- **End to end** on a three-node cluster with the echo slice on every node:
  - every instance defective inside the bake window → exactly one rollback, plus the event and the alert;
  - rollback disabled, or the version outside the bake window → event and alert, no rollback;
  - every instance returning business failures → nothing;
  - the leader killed mid-detection → exactly one rollback by the new leader.
  [verified: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/AutoRollbackOnAllInstancesFailedTest.java`]
  [unverified: the defect stimulus is a missing method invoked through each node's own bridge, not a real broken build, because no two-version test artifact of one artifact base exists]
- **Dead listener hooks.** Removed: `NodeLifecycle.addStateListener`, `BootstrapModule.onBootstrapCommitted` and `SliceInvoker.setFailureListener`. `ObservabilityRegistry.registerNodeCount` and `registerSliceCount` are now registered, so the `aether_cluster_nodes` and `aether_slices_active` gauges the soak dashboards query exist. `LeaderReconciler.setReconcileListener` stays, narrowed to a package-private test seam.
- **Does not detect:** partial failure, business-logic regressions, hangs and deadlocks, asynchronous exceptions inside a slice's own Promise chain, or a version with no traffic. Automatic rollback remains enabled by default, as before; no config binding exists for it yet.
