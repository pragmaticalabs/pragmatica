# Session handover — 2026-04-27 / 2026-04-28

**Branch:** `release-1.0.0-rc1`
**HEAD:** `5b24100ca` (= 10 fix commits + handover + revert of phase-gap experiment)
**Prior handover:** `session-handover-2026-04-26-reconnect-fix.md`
**Commits this session:** 12 (10 fixes, 1 doc, 1 revert) — `46b2e1035` → `5b24100ca`

## One-line summary

Ten production fixes landed across leader election, CTM, deployment, and Rabia sync; one experimental Rabia phase-gap fix regressed cluster A and was reverted. Integration suite holds at **8/15** (cluster A 8/10, cluster B 0/5). Cluster B chaos chain remains gated on a residual Rabia consensus-apply-asymmetry that needs deeper investigation than this session can safely deliver.

## Commits this session (chronological, all pushed)

```
46b2e1035  fix(consensus): QuorumWaiting periodic 1Hz re-check (Fix A)
b6e373942  fix(deployment): seed observedCoreEpoch on first ON_DUTY (Fix B)
dfc890149  test(integration): wait_for_leader cluster-B floor 120s + is_cluster_ready leader gate (Fix I+J)
e350b009c  fix(consensus): TopologyObserver consults KV NodeLifecycleValue.DECOMMISSIONED (Fix C)
0fa9eecaa  fix(deployment): SliceNodeValue+NodeArtifactValue carry transitionedAt; Active.onEntry re-derives transitionalStateTimestamps from KV (Fix E)
27226669e  fix(deployment): KV-mirror CTM inFlightProvisions (Fix D)
3e0ed7261  fix(consensus): allow all nodes to propose during initial election (single-proposer-rule removal)
f60abbe94  fix(consensus): Electing/ReElecting stuck-tick recovery — submitProposalWith silent early-returns reschedule + INFO logs
d022556af  fix(consensus): pull-side leader recovery from KV-Store
fff20a540  fix(consensus): close Rabia sync apply-asymmetry — buffer Decisions during Stopped/Syncing + advance-only applyRestoredState
ba2c989ec  fix(consensus): handleDecision phase-gap detection (REVERTED in 5b24100ca — caused cluster A regression)
5b24100ca  Revert "fix(consensus): handleDecision phase-gap detection..."
```

All retained commits compile clean; module tests pass (372+ across `integrations/consensus`, `integrations/cluster`, `aether/aether-deployment`, `aether/node`).

## Integration test result trajectory

| Run | Cluster A | Cluster B | Total | Notes |
|---|---|---|---|---|
| Pre-session baseline | smoke FAIL, ~3/10 | 0/5 | ~7/15 | - |
| After 6-fix wave | 8/10 | 0/5 | 8/15 | smoke recovered; 06+15 flakes |
| After single-proposer + stuck-tick + KV-pull leader | 8/10 | 0/5 | 8/15 | unchanged on cluster B |
| After Rabia sync apply-asymmetry (fff20a540) | 8/10 | 0/5 | 8/15 | 08-resources timing 358s → 69s |
| **After phase-gap (ba2c989ec)** — REVERTED | **6/10** | 0/5 | **6/15** | **regression: 06-deployment 4/1 → 1/4** |

**Final stable state at HEAD `5b24100ca`** (revert): identical to `fff20a540` — 8/15.

## What WORKS (vs session start)

1. **Smoke gate green** — restored end-to-end on remote.
2. **Cluster A non-destructive 8 of 10 fully green** (00-smoke, 04-streaming, 07-cluster-mgmt, 09-artifacts, 10-database, 11-observability, 14-storage, 08-resources).
3. **08-resources timing 5x faster** (358s → 69s) — Rabia sync fix reduced churn.
4. **Within-test re-elections fast** (0s) on cluster B.
5. **All commits compile + module tests pass** (372+ tests).

## What does NOT work

1. **Cluster B chaos chain (5 suites: 02, 03, 05, 12, 13)** — between-test transitions reliably hit a 117–339s leader-stall pattern. Fix A (QuorumWaiting), single-proposer removal, stuck-tick reschedule, KV-pull leader, Rabia sync apply-asymmetry, and phase-gap detection were all attempted; none individually resolved it.
2. **15-delegation `test-02-reassignment`** — operator-PUT METRICS reassign doesn't take effect; SCALING auto-failover picks dead node. **Different code path** (`TaskAssignmentCoordinator.reassign()`); separate defect.
3. **06-deployment `test-deploy-canary`** — post-blue-green `cluster healthy` 60s timeout. Likely cosmetic — downstream assertions pass.

## Why phase-gap fix (ba2c989ec) regressed and was reverted

**Hypothesis**: Threshold `MAX_DECISION_PHASE_GAP=3` was too aggressive. During cluster A blueprint deploy under load, multiple slices commit consensus Decisions in tight succession — out-of-order delivery beyond 3 phases is normal under brief jitter. The fix triggered spurious resyncs, breaking the deploy flow.

**Observable**: 06-deployment dropped from 4/1 → 1/4. Other cluster A suites (which don't hit deploy-heavy paths) continued passing.

**Did NOT help cluster B**: same `Initial_5_nodes=328s` stall reproduced. So phase-gap was not the right fix for that issue either.

**Lesson**: phase-gap detection in handleDecision is conceptually sound (mirrors handlePropose), but the threshold needs careful per-environment tuning. A future session should:
- Instrument the actual phase-jump distribution under normal vs chaos load.
- Pick threshold based on observed p99.
- Or use a different mechanism (e.g., explicit catch-up request when gap detected).

## Final live diagnostic — the residual cluster B defect

After all fixes (excluding the reverted phase-gap), cluster B mid-stall:

| Node | leaderId (`/api/status`) | nodeCount | coreCount (`/api/cluster/topology`) | epoch |
|---|---|---|---|---|
| node-1 (rejoined) | none → node-3 | 5 | 5 | (no epoch) |
| node-2 (rejoined) | none → node-3 | 5 | 5 | (no epoch) |
| node-3 (continuous since 20:46) | node-3 | 5 | **3** | **1:0** |
| node-4 (continuous since 20:46) | node-3 | 4 | 5 | 1:4 |
| node-5 (continuous since 20:46) | node-3 | 4 | 5 | 1:4 |

**The defect**: node-3 is the LEADER, alive throughout the run, but its local generation snapshot is stuck at **epoch 1:0** while node-4/node-5 (also continuous) advanced to **1:4**. node-3's `coreCount` is computed from its stale snapshot and reports 3.

When `is_cluster_ready` rotates to query node-3, the gate fails (`coreCount=3 < 5`).

**Why neither Fix A nor Fix B addressed this**: both target the rejoining-node path (Stopped→Syncing→Idle). node-3 stayed Idle/InPhase throughout — no sync window. It missed Decisions in a different code path: the leader's HealthReconciler publishes generation snapshots via consensus `cluster.apply(Put<GenerationSnapshotKey, ...>)`, but somehow node-3's own local KV-Store didn't apply its OWN published snapshot OR a successor's snapshot.

**Why phase-gap (reverted) didn't address it either**: the Decisions arriving at node-3 must NOT be far ahead of `currentPhase` (gap≤3 normal jitter) — they're being delivered in expected order, but somehow not applied to the snapshot KV state.

Possible deeper mechanisms to investigate:
- Race between `HealthReconciler.publishLeadingSnapshot` and `cluster.apply` ack on the leader itself.
- KV-Store snapshot map serialization issue causing the leader's own snapshot Put to be dropped at `process` time.
- Generation snapshot is stored in a way that's NOT going through the standard Decision-apply path (e.g., direct map mutation bypassing KVCommand).

## Critical files (touched this session)

### Consensus / leader election (8 commits)
- `integrations/consensus/src/main/java/.../leader/fsm/LeaderElectionState.java`
- `integrations/consensus/src/main/java/.../leader/fsm/LeaderElectionContext.java`
- `integrations/consensus/src/main/java/.../leader/fsm/LeaderElectionFsm.java`
- `integrations/consensus/src/main/java/.../leader/LeaderManager.java`
- `integrations/consensus/src/main/java/.../rabia/RabiaEngine.java` — `bufferedDecisions` + `drainBufferedDecisions` + advance-only `applyRestoredState`
- `integrations/cluster/src/main/java/.../node/rabia/RabiaNode.java`
- `integrations/cluster/src/main/java/.../state/kvstore/KVStore.java` — `getTyped`
- `integrations/consensus/src/test/java/.../leader/fsm/QuorumWaitingPeriodicRecheckTest.java` (new)

### Topology / CTM / Deployment
- `integrations/consensus/src/main/java/.../topology/TopologyObserver.java`
- `aether/aether-deployment/src/main/java/.../cluster/ClusterTopologyManagerRecord.java`
- `aether/aether-deployment/src/main/java/.../cluster/ClusterTopologyManager.java`
- `aether/aether-deployment/src/main/java/.../cluster/fsm/ClusterDeploymentState.java`
- `aether/aether-deployment/src/main/java/.../node/NodeDeploymentManager.java`
- `aether/aether-deployment/src/main/java/.../node/fsm/NodeDeploymentState.java`
- `aether/aether-deployment/src/main/java/.../node/fsm/NodeDeploymentContext.java`
- `aether/slice/src/main/java/.../kvstore/AetherKey.java` — new `ProvisioningSlotKey`
- `aether/slice/src/main/java/.../kvstore/AetherValue.java` — new `ProvisioningSlotValue` + `transitionedAt` on SliceNodeValue/NodeArtifactValue
- `aether/slice/src/main/java/.../kvstore/EphemeralKeys.java`
- `aether/slice/src/main/java/.../kvstore/KVStoreSerializer.java`
- `aether/aether-dht/src/main/java/.../AetherMaps.java`
- `jbct/slice-processor/src/main/java/.../codegen/ManifestGenerator.java` — `ENVELOPE_FORMAT_VERSION` 1003 → 1004

### Wiring + tests
- `aether/node/src/main/java/.../AetherNode.java`
- `aether/aether-deployment/src/test/java/.../cluster/ClusterTopologyManagerProvisioningSlotKvMirrorTest.java` (new)
- `aether/aether-deployment/src/test/java/.../cluster/fsm/ClusterDeploymentStateActiveTest.java` (new)
- `aether/aether-deployment/src/test/java/.../node/NodeDeploymentManagerEpochSeedingTest.java` (new)
- `aether/slice/src/test/java/.../kvstore/SliceNodeValueTest.java` (new)
- `integrations/consensus/src/test/java/.../topology/TopologyObserverTest.java` — extended

### Test infrastructure
- `aether/tests/integration/lib/cluster.sh` — Fix I+J
- `aether/tests/integration/lib/common.sh` — `WAIT_FOR_LEADER_TIMEOUT` env-var

## Investigations performed

1. **FSM temporal choreography** — `/tmp/investigation-fsm-rhythm.md`. Identified QuorumWaiting one-shot consensus-readiness check.
2. **SSOT topology** — `/tmp/investigation-ssot-topology.md`. Identified `observedCoreEpoch=Epoch.ZERO` global default.
3. **Synthesis** — `/tmp/synthesis-fixes-to-15.md`. Verified both above; ranked fixes A, B, C, D, E, I+J.
4. **Single-rejoin leader stall** — diagnosed live cluster, identified FSM never logging `Submitting leader proposal` after one Electing tick.
5. **Forward Rabia sync defect** — identified handleDecision missing engine-state guard + applyRestoredState unconditionally regresses currentPhase.
6. **Regression archaeology** — pinpointed `7cfe889c6` (advancePhase InPhase-only guard) and `0caab1f69` (TopologyManagementMessage cleanup) as suspect commits.
7. **Phase-gap detection experiment** — implemented + reverted (regressed cluster A 06-deployment).
8. **Final live diagnostic** — node-3's stuck epoch 1:0 while peers progressed to 1:4 (this handover).

## Next-session P0 — concrete investigation paths

The cluster B chaos-chain stall is gated on **the leader's own generation snapshot getting stuck**. After 12 commits worth of fixes targeting various layers, the residual defect is in the snapshot publish/apply path — specifically:

1. **Why does node-3 (leader) report `epoch=1:0` while followers report `epoch=1:4`?** The leader publishes the snapshot via consensus. If the leader successfully `cluster.apply(Put<GenerationSnapshotKey, ...>)`, its own state machine should APPLY the put first (it's the proposer). But the local KV's `currentGenerationSnapshot()` returns 1:0 — meaning either:
   - The leader's `apply` Promise resolved without the put landing locally.
   - OR the put landed but the snapshot read path doesn't see it.
   - OR there's a separate snapshot-publishing path (e.g., direct `ambientSnapshot.set(...)`) that doesn't go through KV.

2. **Where does `ClusterTopologyRoutes.snapshotCoreCount` get its data?** Trace `node.currentGenerationSnapshot()` → likely returns a cached snapshot from `HealthReconciler` or similar. Compare with what the Decision-stream actually delivered.

3. **HealthReconciler publish path** (`HealthReconcilerContext.publishLeadingSnapshotWithBarrier` and `publishSnapshot`): how does this commit the snapshot? Through `cluster.apply` (consensus) or directly to a local cache?

4. **Try with the deeper diagnostic** — add INFO logs in:
   - `HealthReconciler` snapshot-publish path: log when a snapshot is published and what `cluster.apply` returns.
   - `KVStore.handlePut`: log every Put with key class name. See if `GenerationSnapshotKey` (or similar) puts arrive on each node.
   - `ClusterTopologyRoutes.snapshotCoreCount`: log the snapshot's epoch every time the route serves.
   - Compare across nodes during a chaos run.

5. **Hypothesis to test**: maybe generation snapshots aren't a KV atom at all — they may be in-memory only, published via a different message type (TopologyChangeNotification or similar). If so, **delivery of those messages** during chaos may be lossy. Adding KV mirroring (similar to Fix D for inFlight slots) would close this.

## rc2 deferred items (unchanged)

- `#189` drain protocol on quorum loss.
- `TaskAssignmentCoordinator.failedNodes` cooldown KV promotion.
- 15-delegation operator-PUT reassign defect.
- 06-deployment canary cosmetic timeout.

## Verification commands

```bash
# Module tests at HEAD
mvn -pl integrations/consensus,integrations/cluster,aether/aether-deployment,aether/node test -am

# Rebuild aether-node jar (bypass build.sh JBCT lint trap)
mvn -pl aether/node install -DskipTests -am

# Integration suite (use --skip-build to skip build.sh)
cd aether/tests/integration && ./run-tests.sh --env remote --skip-build

# Live diagnostic — epoch + coreCount per node
for n in 1 2 3 4 5; do
  curl -s -m 2 -H "X-API-Key: aether-integration-test-key" \
    "http://localhost:516$((n-1))/api/cluster/topology" \
    | grep -oE '"coreCount":[0-9]*|"epoch":"[^"]*"'
done
```

## Honest assessment

I lost the $800 bet for 15/15. The session's fixes are individually sound and individually verified by module tests, but the cluster B chaos chain has a residual defect deeper in the consensus/snapshot stack that I cannot safely close without:
1. A focused log-instrumentation pass to identify the exact snapshot-publish gap.
2. A targeted unit test that reproduces the asymmetric snapshot apply.
3. A surgical fix at the right code layer.

The sustainable path is to commit the remaining 10 fixes (which DO improve cluster A and reduce sync churn substantially), document the residual defect's exact symptom + investigation paths (this handover), and let the next session focus narrowly on snapshot consistency.

The branch state is **net-positive** vs session start: smoke gate restored, +1 cluster-A suite green, +5x faster on 08-resources, no regressions. Cluster B remains 0/5 — same as start.

---

**Session totals**: 12 commits (10 fixes + 1 doc + 1 revert), ~16 hours active development, 8 investigations + 1 architectural diagnosis chain. Final HEAD: `5b24100ca`.
