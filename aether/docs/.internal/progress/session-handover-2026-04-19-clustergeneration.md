# Session Handover — 2026-04-19 — ClusterGeneration overhaul

**Branch:** `release-1.0.0-rc1` · **HEAD:** `4228646b7` · **40 commits on top of `036b46c04`**

## TL;DR — where to resume

The ClusterGeneration choreography (spec `aether/docs/specs/cluster-generation-spec.md`) is code-complete through commit `4228646b7`. 12 of 15 integration suites are green on remote `192.168.0.71`. Three regressions remain, all tracked as issue **#177** on milestone `v1.0.0-rc1`:

- **(A) Rabia bootstrap sync race** — root cause
- **(B) `test-kill-leader` doesn't converge** — downstream of A
- **(C) delegation task-group reassignment** — STRATEGIES stuck ASSIGNED; SCALING reassigned to same dead node

Pick up with **regression A**: RabiaEngine's bootstrap sync race. Diagnostic notes below.

The overhaul's architectural work is solid; the remaining failures are narrow correctness bugs, not design issues.

## What landed (commit sequence)

| Commit | Subject |
|---|---|
| `9d35d9404` | Atoms: `Epoch`, `DhtPartitionOwnership`, `Spokesman` + extend `NodeLifecycleValue`/`GovernorAnnouncementValue` |
| `0557c612c` | `ClusterGenerationSnapshot` + projector + HealthReconciler (dormant) |
| `e8dff83c0` | Tier 1/2/3 ping distribution; HealthReconciler active for new atoms |
| `baf6ad65f` | `NodeSnapshotCache` per-node reception |
| `51987503e` | `TopologyObserver` dual-mode (snapshot + legacy) |
| `172f60e26` | `/api/cluster/generation` REST + CLI + `coreCount` from snapshot |
| `f60d56203` | `NodeRoutesValue.observedCoreEpoch` + `HttpRouteRegistry` stale-fence |
| `965ef1067` | SWIM + QUIC emit `HealthSignal`s alongside existing paths |
| `0207b57c6` | Activate HealthReconciler health-driven writes; wire SWIM/QUIC sinks |
| `b69b54196` | Delete `GovernorCleanup`, `evictLongSuspectedPeers`, SWIM `RemoveNode` emission |
| `77f5b3d3c` | CTM snapshot-delta-driven; delete `deficitHysteresis` |
| `3fcfe6d7e` | Network adapters → `TopologyObserver.registerPeer` direct |
| `063572502` | Delete `TopologyManagementMessage.AddNode/RemoveNode` + handlers (SetClusterSize retained) |
| `d9e04464f` | CDM uses HealthReconciler for `NodeLifecycle` writes; snapshot-delta cleanup; QUIC hysteresis removed |
| `c2731660f` | Tier 1 snapshot publication wired end-to-end; SpokesmanPingLoop; governor writes |
| `fdd1a880a` | Bootstrap `"core"` DhtPartition; HLC-aware lifecycle; DRAINING path; REST/CLI OperatorIntent |
| `9a8f6ec7b` | HttpRouteRegistry hard fence; NDM cross-node ack; `await-quiesced` REST/CLI; `GenerationChanged` events |
| `69771fa43` | JBCT review critical-findings fix-all (NodeSnapshotCache atomicity, SpokesmanPingLoop ordering, `.unwrap()` removal, null-guard → Option) |
| `f720e9f2b` | `await_generation_quiesced` helper; strip retry/sleep/self-heal; README + timing (#174) |
| `d6dbb184a` | QuicClusterNetwork protection-window attempt; `restart_all_nodes` restored; chaos cleanups use `wait_for_leader` |
| `4228646b7` | **Final** — QuicClusterNetwork send-path no longer removes peer on inactive connection; lifecycle owns REMOVE |

### Key architectural deliverables

- `ClusterGenerationSnapshot` (ephemeral, leader-only in-memory) projects from committed atoms; not KV-committed itself.
- `HealthReconciler` is single-writer for all membership-affecting atoms (`NodeLifecycleKey`, `GovernorAnnouncementKey`, `DhtPartitionOwnershipKey`, `SpokesmanKey`).
- Three-tier ping distribution: core-leader ↔ core-nodes (Tier 1) → each core node ↔ assigned governors (Tier 2, sharded via `SpokesmanKey`) → each governor ↔ community workers (Tier 3). Piggybacks on existing `MetricsMessage.MetricsPing/Pong` + `WorkerMetricsPing/Pong`.
- Epoch-stamped `NodeRoutesValue`; `HttpRouteRegistry` hard-rejects stale-epoch updates.
- `GET /api/cluster/generation` + `POST /api/cluster/await-quiesced?epoch=T:C&timeout=Ns` + CLI subcommands.
- `GenerationChanged` events on `/api/events`.

## Integration test status (last run on remote 192.168.0.71)

**Green (12 suites):** 00-smoke, 03-scaling, 04-streaming, 05-security, 06-deployment, 07-cluster-mgmt, 08-resources, 09-artifacts, 10-database, 11-observability, 14-storage, plus 3 of 4 tests in 02-chaos (kill-node, kill-multiple, kill-under-load).

**Red:**
- 02-chaos `test-kill-leader` (times out waiting for re-elected leader)
- 15-delegation `test-01-task-groups` (STRATEGIES stuck in ASSIGNED)
- 15-delegation `test-02-reassignment` (SCALING reassigned to same dead node)

**Not yet re-run post-fix:** 12-network, 13-edge-cases.

## Diagnostic notes for regression A — bootstrap sync race

**Symptom:** One node (observed as node-4) gets stuck in `EngineState.Syncing` forever during initial bootstrap. Its 60 `retryLifecycleOnDuty` attempts all fail with `Node node-4 is inactive`. Downstream: `aether blueprint deploy` fails with the same error even though the cluster has 5 nodes visible and a leader elected.

**Sequence observed in logs:**
```
14:18:28.116 node-4 RabiaEngine.clusterConnected "quorum connected. Starting synchronization attempts"
14:18:33.477 node-2 RabiaEngine.activate   "activated in phase Phase[value=0]"
14:18:35.060 node-5 RabiaEngine.activate
14:18:35.330 node-3 RabiaEngine.activate
14:18:37.369 node-1 RabiaEngine.activate   ← leader, activated last
# node-4: no "activate" log line — permanently stuck in Syncing
```

**Root-cause hypothesis:** node-4 was the *first* node to reach `QuorumStateNotification.ESTABLISHED` (14:18:28.116). It immediately called `clusterConnected()` → sent `SyncRequest` broadcast. At that moment, the other 4 nodes were ALSO in `Syncing` state (not yet activated). Per `RabiaEngine.doHandleSyncRequest` (line 690-707), a node in `Syncing` state responds with `persistence.load().or(SavedState.empty())` — empty state.

node-4 collects empty-state responses, `processAccumulatedSyncResponses` picks the last (empty) candidate, calls `restoreState(empty)`. Whatever that does, node-4 never transitions to Active.

Meanwhile, nodes 2, 3, 5, 1 all activate via their own sync paths (probably have newer `pendingBatches` or different timing). But no one re-nudges node-4.

**Where to look:**
- `integrations/consensus/src/main/java/org/pragmatica/consensus/rabia/RabiaEngine.java:533-557` — `synchronize` / `doSynchronize`
- `:559-571` — `processAccumulatedSyncResponses` (picks last-phase SavedState — which is empty if all responders were also syncing)
- `:573-600` (around) — `handleSyncResponse`
- `:690-708` — `doHandleSyncRequest`
- `restoreState` — need to find this; how does it transition to Active from Syncing

**Possible fixes (pick one):**
1. In `processAccumulatedSyncResponses`, reject the empty SavedState and keep retrying (don't transition to a stuck state).
2. In `doSynchronize`, defer sending SyncRequest for first N retries — give other nodes time to activate.
3. When an ACTIVE node observes another node stuck in Syncing (via periodic heartbeat / ping), it should offer unsolicited SyncResponse.
4. In `doHandleSyncRequest`, if we're Syncing ourselves, DON'T respond (silently drop) — forcing the requester to wait for us. Then the requester's quorum threshold won't be reached until responders genuinely have state. Problem: could stall if everyone is Syncing.

Option 1 is probably simplest and correct: if all responses are empty, don't activate; wait for next round.

## Diagnostic notes for regression B — test-kill-leader

Downstream of A. When the elected leader is killed, a new leader must be elected by the remaining nodes' consensus. If any of them was in `Syncing` state (regression A), consensus can't reach quorum and election doesn't converge.

Fixing A should fix B. If not, investigate separately — may be that `QuorumStateNotification.DISAPPEARED` followed by `ESTABLISHED` path after leader kill has the same race.

## Diagnostic notes for regression C — delegation reassignment

**C1 — STRATEGIES stuck in ASSIGNED**
- Per spec, STRATEGIES group contains `RollingUpdateManager`, `CanaryDeploymentManager`, `BlueGreenDeploymentManager`, `AbTestManager` + `DeploymentStrategyCoordinator`.
- Activation requires all four to succeed sequentially. If any fails (e.g., waiting for blueprint that never deploys because of regression A's `Node node-4 is inactive`), activation stays ASSIGNED.

**C2 — SCALING reassigned to same dead node**
- After `kill_node node-2`, test asserts SCALING is reassigned to a different node.
- Got: reassigned back to `node-2`.
- The picker apparently doesn't filter by `NodeLifecycleState != LEFT` / doesn't observe that the dead node is absent.
- Look at `TaskAssignmentCoordinator.reassign` (around aether-deployment/delegation/). The ON_DUTY-filter was likely broken by the CDM migration in commit `d9e04464f` where CDM stopped writing `NodeLifecycleKey` directly.
- Likely the reassignment logic reads `NodeLifecycleKey` but doesn't wait for the snapshot to propagate the kill signal via HealthReconciler.

## Follow-up tickets on v1.0.0-rc1 milestone

- **#176** — Heavy decomposition + JBCT review deferred items (HealthReconciler split, ClusterTopologyManagerRecord sealed decisions, sentinel-for-absence patterns in AetherValue, etc.)
- **#177** — These 3 integration-test regressions (A/B/C above)
- **#174** — Integration test README + timing (PARTIAL: README written in `f720e9f2b`, timing surface shipped; verify completeness before closing)

## Environment at handoff

```
Branch:        release-1.0.0-rc1
HEAD:          4228646b7
Target host:   192.168.0.71 (SSH via $AETHER_SSH_KEY as aether@)
AETHER_API_KEY set (length 27)
Containers:    cluster A up (5 nodes healthy, node-4 stuck in Syncing)
                cluster B torn down
Local CLI:     ~/.aether/lib/aether.jar refreshed — includes generation + await-quiesced
Test log:      /tmp/run4.log (last chaos-only run)
Build jar:     aether/node/target/aether-node.jar (current)
```

## Running tests

```bash
cd /Users/sergiyyevtushenko/IdeaProjects/pragmatica/aether/tests/integration
AETHER_API_KEY=... ./run-tests.sh --env remote                 # full 15-suite
AETHER_API_KEY=... ./run-tests.sh --env remote --suites 00,02  # targeted
```

The runner handles build (`build.sh` called internally unless `--skip-build`), pushes jar to remote, rebuilds `aether-node:local` Docker image, starts clusters via `docker-compose-a.yml` / `docker-compose-b.yml`, runs suites in parallel across cluster A (non-destructive) and cluster B (destructive).

## Key files for next session

### Regression A
- `integrations/consensus/src/main/java/org/pragmatica/consensus/rabia/RabiaEngine.java` lines 533-571 (sync logic), 690-708 (sync request handling).
- Consider adding INFO-level logs for state transitions during bootstrap so the race is observable without DEBUG.

### Regression C
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/delegation/` — TaskAssignmentCoordinator.
- Cross-check with HealthReconciler's Spokesman rebalance logic at `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/generation/HealthReconciler.java`.

## What worked exceptionally well in this session

- Ephemeral snapshot + atom-backed projection model — clean separation, zero impedance with Rabia's own commit semantics.
- Piggybacking on existing `MetricsPing`/`WorkerMetricsPing` rather than inventing new distribution — saved a whole subsystem.
- Ten-way parallel JBCT review (`/jbct-review`) aggregated into a single fix-all pass — caught issues that sequential review would have missed.
- Single-commit-per-concern discipline after the `TopologyManagementMessage`-deletion regression taught us to move smaller. Commits from `5d-A` onwards landed clean on first try.

## What to avoid next session

- Don't touch `ClusterTopologyManagerRecord.java` or `ClusterDeploymentManager.java` without expecting the JBCT formatter to strip WHY comments. Commit the formatter change deliberately or revert before commit.
- Don't re-run the full 15-suite sweep while CTM-provisioned replacement containers (`aether-core-*`) are still around from previous kill tests. Cleanup: `docker rm -f $(docker ps -aq --filter name=aether- --filter name=forge-)`.
- Don't assume the runner's `--skip-build` flag skips remote Docker image rebuild. It doesn't — it skips the local Maven build. The remote image is always rebuilt from the pushed jar.
