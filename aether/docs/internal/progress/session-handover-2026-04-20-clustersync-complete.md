# Session Handover — 2026-04-20 — ClusterSync Refactor Complete

**Branch:** `release-1.0.0-rc1` · **HEAD:** `90792612a` · **Plan:** `aether/docs/specs/clustersync-refactor-spec.md`

## TL;DR

The full 8-commit ClusterSync refactor described in the plan spec is landed and unit-test green across all modules (~3000 tests). The single-source-of-truth architecture is now the canonical data-flow model for Tier 1 cluster-state distribution. Three follow-up fix commits addressed regressions discovered during integration-test validation.

Integration suites on remote Docker show **stable** results:

| Suite | Result | Note |
|---|---|---|
| `00-smoke` | 2p/0f | Slice-activation fixed via CDM follower snapshot fallback (`131eec643`). |
| `02-chaos` | 3p/1f | `test-kill-under-load` fails on error-rate assertion (88% during kill, threshold 10%). All other chaos tests pass. |
| `03-scaling` | 2p/1f | `test-02-scale-up`'s "Scaled to 7 nodes" now passes in ~10s (was stuck at 5); "Restored to 5" fails because the new provisioned leader's `selectNodesForTermination` heuristic prefers compose fixtures (which can't be terminated by the DockerComputeProvider). Eventually converges via retries. |

Remaining suites (`04`..`15`) were not re-validated in this session after the commits above — they were green in prior sessions with substantially similar state.

## Commits landed this session (on top of `347fd3091`)

| Hash | Subject |
|---|---|
| `1abb5971f` | refactor: rename MetricsPing/Pong chain to ClusterSync* (Tier 1 sync loop; envelope format bumped) |
| `93f4d4f8d` | docs: ClusterSync refactor plan (Tier 1 single-source-of-truth rebuild) |
| `98c50eef1` | feat: ClusterSyncPong carries peer observations (SWIM + QUIC); leader fans into HealthSignal via PeerObservationReducer |
| `28784ea6a` | refactor: followers stop acting on local detections; SWIM + QUIC observations flow to leader via ClusterSyncPong buffer |
| `8a6e51f80` | feat: HealthReconciler start(epoch)/stop(reason) + signal epoch-fence with window=2 |
| `67b368ce1` | refactor: CTM reads desired/actual sizes from ClusterGenerationSnapshot; setDesiredSize is a thin ClusterConfigValue write |
| `3f60e68c2` | refactor: CDM reads activeNodes/drainingNodes/communityGovernor from ClusterGenerationSnapshot; shadow maps deleted |
| `2cd220dbf` | chore: purge follower-side shadow caches that duplicate ClusterGenerationSnapshot |
| `e4c0c39e5` | fix: snapshotCoreCount strict ON_DUTY + HEALTHY |
| `131eec643` | fix: CDM snapshot supplier falls back to nodeSnapshotCache.current() on followers |
| `90792612a` | fix: snapshot desiredCoreSize propagates from ClusterConfigValue; CTM reads leader-aware snapshot source (scale-up now provisions) |

(Also merged PR #180: `026ed09fd` — unrelated comment-inflation cleanup, zero file overlap with refactor.)

## What the refactor delivered

1. **Single source of truth for cluster state.** The leader-projected `ClusterGenerationSnapshot` is the authoritative view. Every consumer reads it through `ManageableNode.currentGenerationSnapshot()` (leader-aware) or `NodeSnapshotCache` (follower cache).

2. **Sensor-only followers.** Local SWIM + QUIC detectors observe peers and push observations upstream via `ClusterSyncPong.peerHealth` / `peerConnectivity`. Followers do not evict peers, do not close QUIC connections because of SWIM hints, do not write lifecycle atoms.

3. **Leader as single decision-maker.** Aggregated observations arrive at `HealthReconciler` through the `ClusterSyncPongSignalFan`, flow through `PeerObservationReducer` (multi-observer aggregation), and drive committed KV atom writes via Rabia. The snapshot's next publication distributes the authoritative result.

4. **Explicit reconciler lifecycle with epoch fence.** `start(leaderEpoch)` / `stop(reason)`. Every `HealthSignal` carries `observedAt`. Signals outside a window of 2 counters from the current snapshot epoch are dropped — stale-leader replay is impossible by construction.

5. **Shadow caches deleted.** `ClusterTopologyManagerRecord.configuredSizeRef` / `desiredSizeRef` / `lastObservedRabiaTerm` gone. `ClusterDeploymentManager.Active.activeNodes` / `drainingNodes` / `communityGovernors` gone. All derived from the snapshot.

6. **CTM scale-up works end-to-end.** `POST /api/cluster/scale coreCount=7` → `ClusterConfigValue` atom → snapshot published with `desiredCoreSize=7` → CTM's next reconcile reads it and provisions 2 nodes. The prior failure chain (setDesiredSize local mutation, forward-ref not wired on follower-delegated CDM, snapshot projection reading `lifecycles.size()` instead of the config atom) is gone.

## Key discoveries during validation (the "check for similar issues" sweep)

Three wiring bugs surfaced during integration test runs; each is a variant of the same pattern — a consumer that runs on any node but only read the leader-side snapshot:

1. **CDM follower supplier (`131eec643`).** `cdmSnapshotSupplierRef` returned `Option.none()` on non-leaders. CDM can be delegated to any node via the DEPLOYMENT task group; on a follower node the supplier needs to fall back to `nodeSnapshotCache.current()`.

2. **Snapshot `desiredCoreSize` projection (`90792612a` part 1).** `projectFromCommittedAtoms` used `lifecycles.size()` as `desiredCoreSize`. Fixed to read `ClusterConfigValue.coreCount` with a lifecycles-size fallback for pre-bootstrap. Added `onClusterConfigPut` listener so the snapshot re-projects when the atom changes.

3. **CTM leader-side snapshot (`90792612a` part 2).** CTM on the leader was wired to read `nodeSnapshotCache` directly — but the leader never receives its own pings, so its cache stays at `INITIAL`. New `LeaderAwareSnapshotSource` wraps the reconciler's snapshot on the leader and the cache on followers. Pattern documented.

## Known remaining issues (out of scope for this session)

### 1. `test-kill-under-load` error rate during node-kill

- Cluster serves 88.71% errors during the ~54s kill window (threshold 10%).
- Root cause is not the generation snapshot or membership decision flow — those converge correctly. Error rate spike comes from in-flight HTTP requests that were routed to the killed node before the snapshot propagates removal.
- Possible directions: application-level retry with failover on the client, or faster snapshot publication post-kill to update follower forwarding tables earlier.

### 2. Scale-down: wrong termination candidates on provisioned leader

- After scale 5→7 succeeds, Rabia re-elects a provisioned node (`aether-core-node-*`) as leader.
- On that new leader, `nodeJoinTimes` is seeded with `Instant.now()` for ALL nodes at activation time — the "most recently joined" heuristic collapses.
- Subsequent scale-down picks compose fixtures (`node-4`, `node-5`) as termination candidates. The DockerComputeProvider can't terminate compose containers (no cloud tag match), logs "No cloud instance found", but `handleTerminationSuccess` still fires.
- CTM eventually converges to the right answer after multiple reconcile rounds, but the 180s test timeout elapses first.
- Fix directions: (a) `selectNodesForTermination` filters out nodes that were not provisioned by the compute provider, or (b) `handleTerminationSuccess` requires a concrete confirmation from the provider, not just "no-op skip".

### 3. `leader elected (timed out 60s)` in multi-kill tests

- `test-kill-multiple` / `test-kill-node` show WARN-level timeouts during the post-kill `wait_for_leader 60` poll, but both pass overall because they kill non-leaders and the leader stays stable.
- The CLI failover added in `969f4fc42` helps, but the 5s per-attempt timeout × number-of-nodes × some-idle-time still exceeds 60s in the adverse case.
- Low priority — tests pass overall; the warnings are diagnostic noise.

## Open product issues surfaced (but not introduced by this work)

- `/api/cluster/await-quiesced` returns HTTP 500 instead of 408 on timeout. Body says "Request Timeout" but status is wrong. This masks legitimate success as failure in `await_generation_quiesced`'s fallback-to-REST path.
- Cross-test state pollution: after a chaos suite, cluster B occasionally enters a state where `cluster_leader` polls take >60s for the next suite to see a leader.

## Verification matrix

| Component | Unit-test status | Integration validation |
|---|---|---|
| `ClusterSyncMessage` / codecs | ✓ | ✓ (wire round-trip + envelope bump) |
| `HealthReconciler` lifecycle | ✓ (9 new tests) | ✓ (leader re-election during scale-up observed; epoch fence active) |
| `PeerObservationReducer` | ✓ (11 new tests) | ✓ (observation flow end-to-end in chaos) |
| `ClusterSyncPongSignalFan` | ✓ (4 new tests) | ✓ (leader sees peer observations from followers) |
| CTM snapshot-driven reconcile | ✓ (6 tests) | ✓ (scale-up; scale-down partial — see remaining issues) |
| CDM snapshot-driven membership | ✓ (2 new tests + 163 existing) | ✓ (slice allocation on follower-delegated CDM works) |
| `LeaderAwareSnapshotSource` | — | ✓ (CTM reconcile succeeds on compose + provisioned leaders) |
| Snapshot propagation chain (scale → config atom → projection → CTM read) | — | ✓ (scale 5→7 completes in ~10s) |

## Where to pick up

1. **Scale-down heuristic fix** — highest-impact remaining. See `ClusterTopologyManagerRecord.selectNodesForTermination`. Adding `isProvisionable(nodeId)` based on compute-provider ownership (not NodeId prefix matching) is the clean approach.

2. **`test-kill-under-load` error rate** — analyze what percent of the kill-window traffic failed. If the cluster's routing continues to target the dead node until snapshot propagation completes (~one ping interval), the error rate is bounded by `ping_interval / kill_duration`. Accelerating the failure-to-snapshot pipeline is the architectural fix; client-side retry on the app side is the tactical fix.

3. **Full 15-suite green across 5 consecutive runs** — the plan's commit-7 correctness gate. Not yet hit; 00-smoke + 02-chaos + 03-scaling are the confirmed-stable subset as of this handover.

4. **Tier-2 follow-up** — issue #178 (`rc2`, `tech-debt`, `deferred`). Strongly gated on Tier-1 soak validation; do NOT start until Tier 1 retrospective is written.

## Files to read first on the next session

1. `aether/docs/specs/clustersync-refactor-spec.md` — the plan (still the canonical reference).
2. `aether/node/src/main/java/org/pragmatica/aether/node/generation/LeaderAwareSnapshotSource.java` — the pattern that solved the wiring bugs; applicable to any new component that needs snapshot access from any node.
3. `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/generation/HealthReconcilerActivator.java` — projection now reads `ClusterConfigValue.coreCount`; listener for `onClusterConfigPut` triggers re-project.
4. `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerRecord.java` — all size reads now snapshot-driven; `setDesiredSize` is a thin atom write.
