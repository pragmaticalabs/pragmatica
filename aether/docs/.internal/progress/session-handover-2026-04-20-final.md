# Session Handover — 2026-04-20 (Final) — ClusterSync Refactor + Scale Fixes

**Branch:** `release-1.0.0-rc1` · **HEAD:** `5139399a2` (63 commits ahead of origin) · **Full run in flight** at handoff time.

## TL;DR

Single-session, multi-hour push. Eight-commit ClusterSync refactor (plan at `aether/docs/specs/clustersync-refactor-spec.md`) **landed in full**. Six follow-up fix commits addressed integration-regression cascades that surfaced during validation. Three previously-stuck integration suites are now either **green** or **substantially improved**; scale-down remains partial.

### Known-green (confirmed stable across multiple runs)

| Suite | Result | Notes |
|---|---|---|
| `00-smoke` | **2p/0f** | Slice allocation works on follower-delegated CDM after the snapshot-supplier fix. |
| `02-chaos` | **4p/0f** | `test-kill-under-load` error rate went from 88% → 0% after the `start_mgmt_load` failover fix. |

### Known-partial

| Suite | Result | Notes |
|---|---|---|
| `03-scaling` | 2p/1f | **Scale-up works end-to-end** (~10s). Scale-down terminates 1 of 2 provisioned nodes, then stalls — the leader's reconciler snapshot and the follower cache get out of sync in a way the current diagnostic path hasn't nailed. See "Remaining issue: 03-scaling scale-down convergence" below. |

### Unvalidated in this session

Suites `04-streaming`, `05-security`, `06-deployment`, `07-cluster-mgmt`, `08-resources`, `09-artifacts`, `10-database`, `11-observability`, `12-network`, `13-edge-cases`, `14-storage`, `15-delegation`. They were green in the post-ClusterSync run on the prior day; a fresh full 15-suite run is in flight at handoff time (log: `/tmp/full-final.log`, PID 66411 — `ps -p 66411` to check liveness).

## Commits landed this session (on top of `347fd3091`)

Plan execution (the spec's 8-commit sequence, re-ordered per §commit-order discussion):

| # | Hash | Subject |
|---|------|---------|
| 0 | `a8af73ba7` | refactor: rename MetricsPing/Pong chain to ClusterSync* (Tier 1 sync loop; envelope format bumped) |
| plan | `9be50aa22` | docs: ClusterSync refactor plan (Tier 1 single-source-of-truth rebuild) |
| 1 | `8ad7706b3` | feat: ClusterSyncPong carries peer observations (SWIM + QUIC); leader fans into HealthSignal via PeerObservationReducer |
| 2 | `263b6b926` | refactor: followers stop acting on local detections; SWIM + QUIC observations flow to leader via ClusterSyncPong buffer |
| 5 | `679ca6ee8` | feat: HealthReconciler start(epoch)/stop(reason) + signal epoch-fence with window=2 |
| 3 | `1aff4b786` | refactor: CTM reads desired/actual sizes from ClusterGenerationSnapshot; setDesiredSize is a thin ClusterConfigValue write |
| 4 | `2f07b8e96` | refactor: CDM reads activeNodes/drainingNodes/communityGovernor from ClusterGenerationSnapshot; shadow maps deleted |
| 6 | `f694dc8f9` | chore: purge follower-side shadow caches that duplicate ClusterGenerationSnapshot |
| 7 | `e4c0c39e5` | fix: snapshotCoreCount strict ON_DUTY + HEALTHY (JOINING workaround removed) |

Regression fixes discovered during integration validation:

| Hash | Subject |
|------|---------|
| `131eec643` | fix: CDM snapshot supplier falls back to nodeSnapshotCache.current() on followers |
| `90792612a` | fix: snapshot desiredCoreSize propagates from ClusterConfigValue; CTM reads leader-aware snapshot source (scale-up now provisions) |
| `1e55ef1cd` | docs: session handover — ClusterSync refactor complete |
| `2b53535cf` | test: start_mgmt_load fails over across core-node ports |
| `6c052b3c6` | fix: scale-down prefers CTM-provisioned nodes for termination; NodeLifecycleManager returns failure (not fake success) when provider has no matching cloud instance |
| `5139399a2` | fix: scale-down termination writes DECOMMISSIONED atom; hard-filter surplus candidates to CTM-provisioned only |

Plus PR #180 merged earlier in the session (`026ed09fd`): unrelated comment-inflation cleanup, zero file overlap.

## Architecture delivered

Unidirectional data flow for cluster-state control plane:

```
sensor (on any node) → observation → pong → leader decision → atom commit → snapshot → ping → follower caches → consumers read
```

Hard invariants now enforced by the code:

1. **One writer**. Rabia leader's `HealthReconciler` is the only component that writes cluster-membership atoms or mutates `ClusterGenerationSnapshot`.
2. **One reader interface**. Consumers read cluster state only through `ManageableNode.currentGenerationSnapshot()` (leader-aware) or `NodeSnapshotCache` (follower cache). Shadow maps derived from KV notifications have been deleted.
3. **Sensors are pure**. Followers' `CoreSwimHealthDetector` + `QuicClusterNetwork` observe peers and push to `ClusterSyncPong.peerHealth` / `peerConnectivity`. Followers do not evict peers, do not close QUIC connections, do not write lifecycle atoms.
4. **Transport hygiene follows the snapshot**. Followers' QUIC peer table updates when `snapshot.coreMembers()` changes, not on local detections.
5. **Decisions are epoch-fenced**. Every `HealthSignal` carries `observedAt`. `HealthReconciler.onSignal` drops signals with `observedAt` outside a 2-counter window of the current snapshot epoch. Prevents stale-leader replay and cross-leader-change signal leakage.
6. **In-flight infrastructure converges via reconcile**, never by cancellation. A mid-flight `docker create` completes; new leader absorbs any transient surplus via its next reconcile.
7. **Explicit reconciler lifecycle**. `start(leaderEpoch)` / `stop(reason)`. Observer signals are fenced against the reconciler's current start-epoch.

### The `LeaderAwareSnapshotSource` pattern

One architectural pattern that should be applied to any NEW consumer of the snapshot: at `aether/node/src/main/java/org/pragmatica/aether/node/generation/LeaderAwareSnapshotSource.java`. Wraps the leader's `HealthReconciler.currentSnapshot()` + follower's `NodeSnapshotCache` behind a single `GenerationSnapshotSource` interface, routed by `isLeader`. Without this, consumers on the leader read an empty `nodeSnapshotCache` (leader never receives its own pings) and short-circuit. All three major consumers (CTM, CDM, status routes) now route through this pattern.

## What surfaced during validation (beyond the plan)

Four wiring-bug classes, all variants of the same pattern:

1. **CDM follower supplier** (`131eec643`). `cdmSnapshotSupplierRef` returned `Option.none()` on non-leaders. CDM is delegated to any node via the DEPLOYMENT task group; the supplier must fall back to `nodeSnapshotCache.current()`.

2. **Snapshot `desiredCoreSize` projection** (`90792612a` part 1). `projectFromCommittedAtoms` used `lifecycles.size()` as `desiredCoreSize`. Fixed to read `ClusterConfigValue.coreCount` with a lifecycles-size fallback. Added `onClusterConfigPut` listener so the snapshot re-projects when the atom changes. Bootstrap seed of `ClusterConfigValue` widened to `coreMin=3, coreMax=15` so scale commands aren't rejected by validator before an operator pushes `cluster-config.toml`.

3. **CTM leader-side snapshot** (`90792612a` part 2). CTM on the leader was wired to read `nodeSnapshotCache` directly — but the leader never receives its own pings, so its cache stays at `INITIAL`. New `LeaderAwareSnapshotSource` wraps both paths. Pattern documented.

4. **Load-generator single-endpoint target** (`2b53535cf`). `start_mgmt_load` hit `${CLUSTER_ENDPOINT}/health/live` which in our Docker setup is a single node's port. Killing that node = 100% errors on the target. Per the spec (`cluster-bootstrap-spec.md` §5.1.7), management API is per-node and the elected-LB floating IP does NOT forward management traffic. Docker env has no `LoadBalancerProvider`, so the test's assumption of LB-fronted mgmt broke. Fixed test-side with per-request failover across core-node ports — matches what a real cloud LB would do.

## Remaining issue: 03-scaling scale-down convergence

**Symptom:** scale 7 → 5 terminates 1 provisioned node, then stalls. Test times out at 180s expecting 5 nodes but sees 7.

**What works:**
- `scale_cluster 5` writes `ClusterConfigValue.coreCount=5` atom correctly.
- Leader's CTM sees `desired=5, actual=7` → `handleSurplus` fires → picks a provisioned candidate (my prefix filter works).
- Compute provider terminates the chosen container.
- `handleTerminationSuccess` fires → `writeDecommissionedAtom` writes `NodeLifecycleKey=DECOMMISSIONED`.
- `HealthReconciler` sees the DECOMMISSIONED lifecycle transition.

**What breaks:**
- Only 2 provisioned nodes existed post-scale-up: `aether-core-node-0-*` and `aether-core-node-1-*`. Rabia re-elected leader to `aether-core-node-1-*` (provisioned) after scale-up.
- Self-exclusion in `selectNodesForTermination` rules out the leader itself → only 1 terminable candidate (`aether-core-node-0-*`).
- After termination: cluster is 6 nodes (5 fixtures + leader). Surplus = 1, but no more terminable candidates. Stall.
- Meanwhile the follower-cached snapshot on 5161/5162 shows stale `desiredSize=7, members=5 fixtures` — inconsistent with the leader's view. Current hypothesis: the leader's ClusterSyncPing hasn't published the post-DECOMMISSIONED snapshot to followers yet, or the projection at ping time read a stale `kvStore.snapshot()`.

**Two avenues to investigate next session:**

1. **Leader-election stability during scale-up.** If leadership stays on a fixture (e.g. `node-1`) instead of migrating to a provisioned node, self-exclusion doesn't lose a termination candidate. The migration happens because new provisioned nodes cause a quorum-reshuffle Rabia election. Question: should we pin leadership to fixture nodes when possible? Or can the leader migration be deferred?

2. **Follower-cache staleness on port 5161/5162 after repeated reseeds.** Sequence of reseeds (ClusterConfig PUT → re-project, NodeLifecycleKey PUT × N → re-project, etc.) may race against the `ClusterSyncScheduler`'s ping-send cadence. If the scheduler grabs `healthReconciler.currentSnapshot()` between two re-projections and publishes the intermediate state, followers lock in on that. Verify by tracing the epoch counter sequence at the follower cache vs. the leader's reconciler.

**Relevant code paths:**
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerRecord.java` (reconcile loop, selection, termination).
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/generation/HealthReconcilerActivator.java` (re-project triggers).
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/generation/HealthReconciler.java` (`reseedMembership`, epoch bump).

## Open product issues (diagnosed, not introduced)

1. **`/api/cluster/await-quiesced` returns HTTP 500 instead of 408 on timeout.** Body is "Request Timeout" but status is wrong. Caused by HttpError.httpError(HttpStatus.REQUEST_TIMEOUT, ...) mapping. Blocks `await_generation_quiesced`'s fallback path; surfaces as WARN only (doesn't fail tests).

2. **Bootstrap seed overwrites operator config.** On first leader-activation, `HealthReconcilerActivator.seedClusterConfigIfMissing` writes a `ClusterConfigValue{coreCount=<topology-size>, coreMin=3, coreMax=15}` atom. The test's `seed_cluster_config` helper then skips pushing `cluster-config.toml` because config is "already present". Operator workflow may be surprised that their `[cluster.core] max = N` setting doesn't apply until they explicitly POST to `/api/cluster/config` with a different version. Consider either: (a) deploymentType-aware merge, (b) don't seed at all and require operator push.

3. **Leader migration on scale-up → CTM heuristic collapse.** `nodeJoinTimes` is seeded with `Instant.now()` on activate, so all topology nodes have the same timestamp on a provisioned leader. The "most recently joined" eviction heuristic falls through to deterministic tiebreakers that don't prefer provisioned over fixtures. Current workaround is the NodeId-prefix filter in `isProvisionedByCtm`. Long-term fix: use actual provisioning timestamps from the compute provider's metadata, not CTM's local `Instant.now()`.

## Environment at handoff

- Remote: `192.168.0.71` (your TARGET_HOST).
- Cluster A + B on remote, currently mid-full-suite-run (PID 66411, log: `/tmp/full-final.log`).
- Remote image `aether-node:local` has commit `5139399a2`'s jar baked in.
- Parent shell exports: `AETHER_API_KEY`, `AETHER_SSH_KEY`, `TARGET_HOST=192.168.0.71`.

## Quick diagnostic commands

```bash
# cluster-B snapshot state on each node
for p in 5160 5161 5162 5163 5164; do
  echo "port $p:"
  curl -s -m 3 -H "X-API-Key: $AETHER_API_KEY" http://192.168.0.71:$p/api/cluster/generation \
    | python3 -c "import sys,json; d=json.load(sys.stdin); c=d.get('core',{}); print(f'  epoch={d.get(\"epoch\")} desired={c.get(\"desiredSize\")} members={len(c.get(\"members\",[]))}')"
done

# ClusterConfigValue state
curl -s -H "X-API-Key: $AETHER_API_KEY" http://192.168.0.71:5160/api/cluster/config | python3 -m json.tool

# CTM activity on all cluster-B nodes
ssh -i "$AETHER_SSH_KEY" aether@192.168.0.71 \
  "for c in \$(docker ps --format '{{.Names}}' | grep -E 'aether-b-|aether-core-'); do
     L=\$(docker logs --since=10m \$c 2>&1 | grep -E 'became leader|handleSurplus|handleDeficit|terminat|DECOMMISSION' | tail -5)
     if [ -n \"\$L\" ]; then echo '=='\$c'==' && echo \"\$L\"; fi
   done"

# Quick targeted run (skips full re-deploy; cluster must be up)
cd aether/tests/integration && ./run-tests.sh --env remote --suites 03 --skip-build

# Full reset
ssh -i "$AETHER_SSH_KEY" aether@192.168.0.71 \
  "docker compose -f ~/docker-compose-b.yml down -v >/dev/null 2>&1
   docker compose -f ~/docker-compose-a.yml down -v >/dev/null 2>&1
   docker rm -f \$(docker ps -aq --filter name=aether-core-) 2>/dev/null
   echo reset"
```

## How to resume

1. **Check the full run result first.** `wc -l /tmp/full-final.log` and `cat test-results.json` tell you if the overnight run finished. If it did, read the result summary and address any new failures.

2. **If 03-scaling is still 2p/1f,** dig into the snapshot consistency hypothesis (§"Remaining issue" above). Start by instrumenting `reseedMembership` with a trace log that prints `current.epoch` → `bumped.epoch` and the new `desiredCoreSize`. Match against the follower cache's epoch at the same wall-clock time.

3. **If other suites regressed,** run them in isolation (`--suites N,00`) and grep CTM/CDM/HealthReconciler logs for the specific failure signature.

4. **Tier 2 follow-up** lives in GitHub issue #178 (`rc2`, `tech-debt`, `deferred`). Strongly gated on Tier-1 soak validation — do NOT start until Tier 1 has 5 consecutive 15/15 green runs.

## References

- Plan: `aether/docs/specs/clustersync-refactor-spec.md`
- Prior handover: `aether/docs/internal/progress/session-handover-2026-04-20-clustersync-complete.md` (earlier-in-day snapshot; this doc supersedes it)
- Issue #178: Tier-2 refactor deferred to RC2
- Spec reference: `aether/docs/specs/cluster-generation-spec.md` §7 (ping/pong), §8 (decision table)

## Tier-1 retrospective seed (for issue #178)

Lessons learned from applying the pattern in Tier 1, useful to consult before Tier 2:

1. **"Leader-aware supplier" is THE pattern.** Any component that needs to read cluster state and might run on any node (CTM, CDM, route handlers, task-group-delegated components) must read through a supplier that routes by leadership. Direct `nodeSnapshotCache` reads break on the leader; direct `healthReconciler.currentSnapshot()` reads break on followers. Always combine.

2. **Re-project on EVERY atom change that affects snapshot fields.** The initial plan enumerated lifecycle atoms; `ClusterConfigValue.coreCount` was missed. Tier 2 must enumerate the per-community atom types and wire re-project handlers for each.

3. **KV writes should be a thin atom write, NOT an in-memory mutation.** `CTM.setDesiredSize` used to mutate a local `AtomicInteger` + commit atom. Refactoring to atom-only removed the drift. Tier 2 governor-side state (community term, spokesman assignments) should follow the same discipline — state lives in atoms, not in governor memory.

4. **Physical termination needs an explicit lifecycle atom write.** The compute provider kills the container but doesn't transition the node's `NodeLifecycleKey`. Without a post-terminate write, the snapshot counts the dead node as ON_DUTY forever. Tier 2: community member removal similarly needs an atom transition, not just a container kill.

5. **`Option.none()` fallbacks silently mask bugs.** Several spots use `.or(List::of)` or `.or(0)` to handle "snapshot not yet projected". These hide real wiring issues (like the CDM supplier returning none unexpectedly). Add assertions or log warnings when the fallback fires — makes subsequent debugging much faster.

6. **Integration-test validation trumps unit-test validation for architectural refactors.** Every unit test passed after each of the 8 commits. Four distinct wiring bugs only surfaced when integration suites ran against real Docker clusters. Budget 2-3× the unit-test validation time for integration soak before claiming done.
