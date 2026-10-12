### Added (2026-10-12 — #2174: deployment state machine rewrite, phase 2b: the pure cluster FSM)
- **The leader's deployment state machine as a pure transition function**, `ClusterFsm.step(state, input) -> (state', effects)`, in the new package `org.pragmatica.aether.deployment.cluster.fsm2`. Effects are data (`Submit`, `Route`, `Warn`, `Audit`, `Log`, `Schedule`, `Cancel`, `Metric`, `Gauge`, `Enqueue`, `RequestRebuild`, `DispatchMigration`); the clocks (wall and monotonic) and the jitter are injected; stale inputs are dropped by incarnation; `Reconcile` and the end-of-drain `Flush` are coalesced; `Rebuild` is the first mailbox item. The model is an immutable value over a persistent hash map, so a single input costs O(log n) and never scans the store.
  [mechanism: `ClusterFsm`, `ClusterModel`, `PMap`; `ScanAndMetricsTest` counts scans per input]
- Every leader write is a `LeaderTransaction` with the committed `LeaderValue` and the exact expected values of the mirror (R-5.7.1); refusals are re-derived per pair (R-5.7.2), a deposed leader suspends instead of resubmitting.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/deployment/cluster/fsm2/WritePathTest.java`, `GapEngineTest.java`]
- The old `ClusterDeploymentState`, `NodeArtifactKey` and its readers are untouched and nothing is wired to the new FSM: this phase changes no behaviour. [mechanism: no production caller of `fsm2`]
- 228 tests on the real `KVStore` applier (no running cluster), every NT row that belongs to the cluster FSM and can be asserted without wiring; the rest are listed in the phase report as phase 3.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/deployment/cluster/fsm2/`]

### Changed (2026-10-12 — #2174: `ArtifactJudgmentValue.retries` is per node)
- **`ArtifactJudgmentValue.retries` is `Map<NodeId, Integer>`, not one `int`** (spec R-3.3.7: the counter is per (artifact, node)). One count per artifact would add the transient failures of different nodes into a cluster-wide condemnation of a slice that is healthy elsewhere, the defect #922 removed. The record shipped in phase 1 and nothing reads or writes it yet; the wire-assignment baseline line for it changed accordingly (its pin, 2151, did not).
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/DeploymentFsmKvRecordsCodecTest.java`, `WireAssignmentTripwireTest`]
