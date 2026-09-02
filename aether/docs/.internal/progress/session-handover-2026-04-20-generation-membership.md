# Session Handover — 2026-04-20 — Generation Membership + Scale Promotion

**Branch:** `release-1.0.0-rc1` · **HEAD:** `47e31092a` (+3 commits this session on top of `a984ff158`) · **Pending full-suite re-run** (running as of handover write-up)

## TL;DR — where to resume

Integration test state moved from **11/15** (prior session) to **12/15** (verified) green on remote Docker (`192.168.0.71`). Four commits landed; the root cause of the prior `test-kill-leader` failure was a **generation-snapshot membership bug** where nodes registering `ON_DUTY` after leader election never made it into the leader's `ClusterGenerationSnapshot.coreMembers()`. Fixing that, plus CLI failover in the test harness, unblocked `02-chaos` entirely.

Remaining **3 failing suites** (`03-scaling`, `12-network`, `13-edge-cases`) have distinct root causes documented below.

## Commits this session

| Hash | Subject |
|------|---------|
| `48e3342f0` | `fix: ClusterGeneration snapshot tracks membership added after leader election (reseed on NodeLifecycleKey PUT); routes read leader view via currentGenerationSnapshot()` |
| `2b632fb80` | `fix: QuicClusterNetwork strict ConnectionDirection + SWIM-authoritative disconnect; SWIM registers dynamically-learned peers; hostname for selfInfo; Rabia restoreState carries phase on empty snapshot` |
| `969f4fc42` | `test: integration runner resilience — CLI failover + short request-timeout, no-cache image build, best-effort post-suite quiesce, restart_all_nodes settles node count` |
| `47e31092a` | `fix: CDM reads live ClusterConfigValue.coreCount for role assignment (scale 5->7 now promotes new cores); test helper dispatches to cluster endpoint when leader is not host-exposed` |

## Root cause: generation snapshot never reseeded after leader election

`HealthReconcilerActivator.onNodeLifecyclePut` previously filtered out every state
transition except `DECOMMISSIONED`. With 5 nodes writing `ON_DUTY` atoms on
startup — some of them landing *after* the leader election fires — the leader's
initial `projectFromCommittedAtoms()` sampled at election time captured only the
1–2 lifecycles that had committed so far. Every later `NodeLifecycleKey` PUT
was silently dropped, so `snapshot.coreMembers()` stayed at `{node-1, node-2}`
forever and `snapshot.desiredCoreSize()` stuck at 2.

Follow-on symptoms (all resolved by the fix):

- `/api/cluster/generation` on the leader returned `desiredSize=0, members=0`
  because the leader never ingests its own metrics pings, so
  `nodeSnapshotCache.current()` stayed at `INITIAL`. Followers' caches showed
  the (broken) 2-member projection the leader had broadcast.
- `cluster_node_count` (via `/api/cluster/topology.coreCount`) fell from 5 to 2
  when the killed leader's container rolled over, because the topology endpoint
  was driving off the same stale snapshot.
- `await_generation_quiesced current+1 60` warned `did not quiesce after blueprint deploy` because blueprint writes never move the generation counter, and the 2-member snapshot could never advance.
- `test-kill-leader`'s `wait_for_leader 150` timed out because `cluster_leader`
  uses `aether_field status cluster.leaderId`, and `aether_failover` targeted
  `LB_MGMT_ENDPOINT` (which pointed at the dead leader's port when no real LB
  was deployed) with no per-call timeout — every poll burnt 60s before failing.

### Fix layers (commit `48e3342f0`)

1. `HealthReconciler.reseedMembership(ClusterGenerationSnapshot freshProjection)`
   — new public method that diffs `coreMembers` and `desiredCoreSize` against
   the current snapshot and, if either changed, replaces them via
   `updateAndBump(...)` with `GenerationReason.MEMBER_ADDED`/`MEMBER_REMOVED`.
2. `HealthReconcilerActivator.onNodeLifecyclePut` calls
   `reconciler.reseedMembership(projectFromCommittedAtoms())` on *every*
   lifecycle transition. The existing DECOMMISSIONED log path is preserved.
3. `ManageableNode.currentGenerationSnapshot()` — new accessor. On the leader
   it returns the reconciler's live snapshot; on followers it returns the
   `NodeSnapshotCache` (ping-fed) snapshot. Wired through a
   `generationSnapshotSupplier` field on the `aetherNode` record.
4. Route sources (`ClusterGenerationRoutes`, `ClusterTopologyRoutes`,
   `ClusterAwaitQuiescedRoute`) read `currentGenerationSnapshot()` instead of
   `nodeSnapshotCache().current()`, so `/api/cluster/generation` on the leader
   now reports the real membership view.
5. `ClusterTopologyRoutes.snapshotCoreCount` counts members whose lifecycle is
   `ON_DUTY` **or** `JOINING`. A transient SWIM `SUSPECTED` hint no longer
   pulls `coreCount` below the actual membership — without this, brief flap
   during chaos recovery masks a perfectly legitimate 5-node cluster as 3.

### Regression surface changed

- Two proxy-based tests (`ClusterGenerationRoutesTest`, `ClusterTopologyRoutesCoreCountTest`) now route
  `currentGenerationSnapshot` through the cache proxy — extended in place.
- `HealthReconcilerActivatorTest.onNodeLifecyclePut_onDuty_logsButDoesNotReact`
  was renamed to document that we *do* re-project on ON_DUTY now (it still
  asserts no consensus batch fires, which remains true because `reseedMembership`
  is a local-only update).

## Pruned test-infra workaround (commit `969f4fc42`)

`aether_failover` in `lib/common.sh` now always retries direct node ports
after an LB call fails, and every CLI invocation carries
`--request-timeout=5` (tunable via `AETHER_CLI_TIMEOUT`). Without this the
`LB_MGMT_ENDPOINT="$cluster_endpoint"` fallback — when no true LB was deployed —
wedged every poll against a dead leader for 60s per attempt, making 150s
windows fire at most twice.

## Scale-up/down (commit `47e31092a`)

Follow-up root-cause once `test-kill-leader` was green: `POST /api/cluster/scale
coreCount=7` wrote the new `ClusterConfigValue` but the CDM's `shouldPromoteToCore`
predicate used the *static* `coreMax` from `ClusterFormationConfig` (configured
once at startup to `5`). Newly provisioned containers joined the cluster over
QUIC, received an `ActivationDirective` → WORKER (observer-mode Rabia), and could
never write their own `NodeLifecycleKey=ON_DUTY` (`Node ... is inactive` retry
storm visible in logs).

Fix: `CDM.Active.effectiveCoreMax()` reads
`kvStore.get(ClusterConfigKey.CURRENT).map(ClusterConfigValue::coreCount)` and
falls back to the static `coreMax` if no config atom is seeded yet. With this
in place, `scale 5 → 7` completes in ~8s and `Cluster healthy at 7 nodes` passes.

`leader_api_post` was also updated to route via the cluster endpoint when the
leader is a CTM-provisioned node (`aether-core-node-*`) that has no host-exposed
port.

**Still failing — scale-down (7 → 5):** CTM's `handleSurplus` never fires on a
leader that lives on a CTM-provisioned container. Manually verified: after
`scale_cluster 5`, the `TopologyObserver.handleSetClusterSize` log fires on
both the provisioned leader *and* node-1, but CTM's `reconcile()` on the
provisioned leader produces no `handleSurplus: Cluster at 7/5, terminating ...`
log. Hypothesis is that `ClusterTopologyManager` on a provisioned node was
constructed with the worker path and never activates as a scaling reconciler
when elected leader — needs a trace through `onLeaderChange` for the provisioned
node. See the `TaskGroupActivator` / `activateOnLeaderChange` path in
`AetherNode.java`.

## Current failing suites

| Suite | Last result | Primary failing assertion | Likely root cause |
|-------|-------------|---------------------------|-------------------|
| `03-scaling` | 2p / 1f | `Restored to 5 nodes: got '7'` (scale-down after scale-up) | `ClusterTopologyManager.handleSurplus` does not fire when leader is a CTM-provisioned node; manual repro confirmed. |
| `12-network` | 1p / 2f | `Quorum broken during window`; `Cluster healthy after QUIC recovery: got 'unhealthy'` | SWIM thrashing under QUIC chaos: multiple duplicate `onMemberFaulty` events per node, `isLocalDisconnect` circuit breaker fires too late. My generation-snapshot fix exposes the underlying instability that was previously masked by a broken snapshot. |
| `13-edge-cases` | 1p / 2f | `await-quiesced status=500 after 61000ms`; drain 503 over budget | 500 on await-quiesced comes from the route's `timeoutResponse()` — it uses `HttpError.httpError(HttpStatus.REQUEST_TIMEOUT, ...)` which downstream serializes as 500 instead of 408. Separately, `test-disruption-budget` fails on budget accounting. |

### 12-network detail

`docker logs aether-b-node-1` during `test-quic-connectivity`:

```
23:04:34 SWIM member faulty: node-2  → DisconnectNode
23:04:39 SWIM member faulty: node-3  → DisconnectNode
23:04:44 SWIM member faulty: node-3  → DisconnectNode   # duplicate
23:04:49 SWIM member faulty: node-3  → DisconnectNode   # duplicate
23:04:50 SWIM member faulty: node-3  → DisconnectNode   # duplicate
23:04:53 Local disconnect detected: 3/5 peers FAULTY within 15000ms — suppressing topology drain for node-3
```

Three duplicates for a single kill in five seconds. The circuit breaker at the
bottom fires *after* the first three FAULTY events already ran
`processViewChange(REMOVE, ...)`. The real fix probably belongs in
`CoreSwimHealthDetector.onMemberFaulty` — dedupe by `(nodeId, faultyWindow)`
before emitting, not after.

### 13-edge-cases `await-quiesced` 500

Repro:

```bash
curl -s -o /tmp/out -w "status=%{http_code}\n" -X POST \
  -H "X-API-Key: $AETHER_API_KEY" -m 5 \
  "http://192.168.0.71:5160/api/cluster/await-quiesced?epoch=1:0&timeout=3s"
```

Body: `{"error":"Request Timeout: await-quiesced timed out before reaching requested epoch"}`, status: `500`.

`ClusterAwaitQuiescedRoute.timeoutResponse()` uses
`HttpError.httpError(HttpStatus.REQUEST_TIMEOUT, ...)` but the HTTP server
serializes it as 500. Either `HttpError` isn't honoring the status code, or
`HttpStatus.REQUEST_TIMEOUT` isn't mapped correctly. Investigate
`http/http-client` and `http/http-core` for the status-code routing — the
resolution is likely outside the aether module.

## Files that matter for next session

- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/generation/HealthReconciler.java` — `reseedMembership`
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/generation/HealthReconcilerActivator.java` — `onNodeLifecyclePut` now always re-projects
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterDeploymentManager.java` — `effectiveCoreMax()` reads `ClusterConfigValue.coreCount`
- `aether/node/src/main/java/org/pragmatica/aether/node/ManageableNode.java` — `currentGenerationSnapshot()`
- `aether/node/src/main/java/org/pragmatica/aether/api/routes/ClusterTopologyRoutes.java` — `snapshotCoreCount` now counts `ON_DUTY|JOINING`
- `aether/tests/integration/lib/common.sh` — `aether_failover` with failover + request-timeout
- `aether/tests/integration/lib/cluster.sh` — `leader_api_post` fallback for CTM-provisioned leaders

## Environment at handoff

- Cluster A + B up on remote `192.168.0.71` after the final full-suite run.
- Remote image `aether-node:local` built from local jar with timestamp `02:30` (commit `47e31092a`'s jar).
- Parent shell exports: `AETHER_API_KEY`, `AETHER_SSH_KEY`, `TARGET_HOST=192.168.0.71`.

## Recommended next steps

1. **Scale-down (03-scaling).** Trace `ClusterTopologyManager.onLeaderChange` /
   `activateWithCurrentTopology` on a CTM-provisioned node. The `setClusterSize`
   message reaches the leader but `reconcile()` never transitions through
   `handleSurplus`, meaning `actual == configured` at that moment. Likely
   cause: observer on the provisioned leader hasn't refreshed its snapshot
   view to reflect the new `ClusterConfigValue` before `reconcile()` polls.

2. **SWIM duplicate-FAULTY (12-network).** Add dedupe in
   `CoreSwimHealthDetector.onMemberFaulty` so the same `nodeId` within a
   `suspectTimeout` window only fires one `DisconnectNode`. That prevents the
   cascade from crossing the `> totalMembers/2` breaker threshold needlessly.

3. **await-quiesced 500 (13-edge-cases).** Confirm whether `HttpError` maps
   REQUEST_TIMEOUT correctly. If not, either wire it up or switch the route
   to explicitly return `408` via the HTTP primitive that actually works.
