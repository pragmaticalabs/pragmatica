### Fixed (2026-09-28 — #1573: automatic rollback, the SliceFailure event and its alert could never fire)
- **The all-instances-failed signal had no producer and no consumer path.**
  - `SliceInvoker`'s failure listener was never wired.
  - Its only producer, the failover path, is reached only by `invokeWithRetry`, which has no production caller.
  - `AlertManager.onAllInstancesFailed` was never routed.
  - The SliceFailure event and the alert therefore never fired, and neither did automatic rollback.
- **A leader-side detector is now the one producer.**
  - Each node counts slice executions and ships the counts on its cluster-sync pong. It counts successes and *defects*. A defect is a failure the runtime produced: the method threw, a codec failed, or the method is missing.
  - A failure the slice RETURNS is neutral at both ingresses — never a defect and never a success — whether it is a failed Promise or a successful one carrying a `Result.Failure` value; counting it as a success would let a version returning errors veto a rollback its thrown defects justify. [verified: `aether/aether-invoke/src/test/java/org/pragmatica/aether/http/HttpRoutePublisherOutcomeTest.java`]
  - A request record built from path/query values without a validating factory is now constructed through a lift in the generated route: a constructor that throws on client input is a typed 400 and is not counted; only the slice method's own throw is a defect. A slice compiled before this change still counts such a constructor throw as a defect until rebuilt. [verified: `jbct/slice-processor-tests/src/test/java/com/example/factoryslice/GeneratedConstructorLiftRuntimeTest.java`]
  - Every ingress is counted. Inter-slice, topic and scheduled executions are counted at the bridge's admission boundary. HTTP routes call the typed slice instance directly, never the bridge, so they record into the same counters from the route handler; a handler that throws is a defect there too. The two share no call site short of proxying every generated slice type. Without HTTP counting, HTTP successes could not veto a rollback, and a version broken only on its HTTP path was never rolled back.
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
  - `PreviousVersionValue` now carries the rollback count, the last rollback time and the failed versions. They commit in ONE leader transaction with the rollback's SliceTarget change, record first, so cooldown and maxRollbacks hold across a leader change. The record is cluster state and is backed up (`BackupEntryCodec` carries the new fields in the value's codec bytes).
  - The transaction is fenced on what the decision was made from: the committed cluster config (the `[rollback]` policy's source), the SliceTarget still naming the failed version, the rollback record as read, and this node being the committed leader. A disable or a newer target committed in between refuses it; a refused rollback is a DEBUG line and no event.
  - This is a KV codec change to `PreviousVersionValue` (wire SHAPE pin updated; tag unchanged).
  [verified: `aether/aether-control/src/test/java/org/pragmatica/aether/controller/RollbackManagerAutoRollbackSafetyTest.java`]
  [unverified: the cooldown and budget are read from the manager's own tracked state; the fence proves the record did not change between its read and the commit, not that the tracked state matched it]
- **End to end** on a three-node cluster with the echo slice on every node:
  - every instance defective inside the bake window → exactly one rollback, plus the event and the alert;
  - rollback disabled, or the version outside the bake window → event and alert, no rollback;
  - every instance returning business failures → nothing;
  - the leader killed mid-detection → exactly one rollback by the new leader;
  - HTTP successes on every node while each bridge sees only defects → nothing;
  - a leader that does not own the cluster-events partition → both events still reach the stream.
  [verified: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/AutoRollbackOnAllInstancesFailedTest.java`]
  [unverified: the defect stimulus is a missing method invoked through each node's own bridge, not a real broken build, because no two-version test artifact of one artifact base exists]
  [unverified: that the previous version is actually running after a rollback; the proof stops at the committed target, because the seeded previous version is not a real artifact]
  [unverified: a version broken ONLY on its HTTP path rolling back end to end; no deployed test slice has a defective HTTP route, so this is pinned at the route recorder (`aether/aether-invoke/src/test/java/org/pragmatica/aether/http/HttpRoutePublisherOutcomeTest.java`) and the detector, not on a cluster]
- **Dead listener hooks.** Removed: `NodeLifecycle.addStateListener`, `BootstrapModule.onBootstrapCommitted` and `SliceInvoker.setFailureListener`. `ObservabilityRegistry.registerNodeCount` and `registerSliceCount` are now registered, so the `aether_cluster_nodes` and `aether_slices_active` gauges the soak dashboards query exist. `LeaderReconciler.setReconcileListener` stays, narrowed to a package-private test seam.
- **Does not detect:** a version that fails by RETURNING errors, without throwing — it is never auto-rolled back, whatever HTTP status those errors map to (a downstream outage fails the same way, and must not roll a healthy version back); partial failure, business-logic regressions, hangs and deadlocks, asynchronous exceptions inside a slice's own Promise chain, or a version with no traffic.
  - A dead hosting node stops counting as a host only once membership declares it DEAD. Until then its stale metrics make the version undecidable. That includes the cold-boot window, where a never-healthy peer reads UNKNOWN.
- **Automatic rollback is ON by default** (owner ruling).
  - It triggers only on the narrow rule above: every live instance of a version recent enough to be inside its bake window reports bridge-level defects with no success.
  - To turn it off, commit this in the cluster TOML; it takes effect at the next decision, with no restart:
    ```toml
    [rollback]
    enabled = false
    ```
  [verified: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/AutoRollbackOnAllInstancesFailedTest.java`, where a blank seed TOML rolls back and a committed `enabled = false` does not]
- **Rollback policy is a cluster-wide `[rollback]` section of the committed cluster TOML.**
  - Keys: `enabled`, `trigger_on_all_instances_failed`, `cooldown`, `max_rollbacks`, `bake_window`.
  - It is typed and validated at apply. A mistyped value, a negative count, a non-positive bake window or an unknown key refuses the apply.
  - The per-node rollback setting (`AetherNodeConfig.rollback`) is removed, and so is the node timeout `timeouts.rolling_update.rollback_cooldown`, which was parsed and never read. There is now one knob.
  [verified: `aether/aether-config/src/test/java/org/pragmatica/aether/config/cluster/RollbackPolicyParserTest.java`]
- **Every committed rollback emits a CRITICAL `AUTO_ROLLBACK` cluster event.** It names the artifact, the from and to versions, and the evidence: each hosting node's defect count and the window. It and the `SliceFailure` event are emitted leader-gated, because only the leader produces them; under the owner gate they were lost whenever another node owned the cluster-events partition. [verified: same forge test, `ClusterEventAggregatorTest` and `RollbackManagerAutoRollbackSafetyTest`]
