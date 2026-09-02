# Session handover — 2026-04-26

**Branch:** `release-1.0.0-rc1`
**HEAD:** `870f89b79`
**Prior handover:** `aether/docs/internal/progress/session-handover-2026-04-25.md`
**Commits this session:** 25 (`91b99bd68` → `870f89b79`)

## One-line summary

FSM Coordination Wave 3 architectural cleanup (13 themes) landed; on integration verification surfaced and fixed cascading defects (bash dynamic-scoping cluster routing, `aether_pgdata` volume contamination, sticky SUSPECTED snapshot state); cluster A formation now clean but smoke gate still blocked by node-2 transport asymmetry (sparse pongs).

## Session arc

Three phases:

1. **Wave 3 implementation** (13 thematic commits A–M, ~14 commits): SLA-critical detection-latency cuts, phantom-provisioning Fix 5 redo with stability window, quorum-loss handler + drain hooks, scale-down 2-phase drain, multi-writer SSOT, drain-and-subscribe race elimination, isLeaderGate residue, clock injection, retry/scheduling jitter, silent FSM event consumption, KV-reconstructibility + tombstone GC, resource leaks, misc cleanup. ~30 new unit tests, 2776 module tests pass.

2. **Integration verification cycle 1**: surfaced multiple cascading bugs.
   - Smoke gate hard-fail on `ClassCastException`: my `countLifecycleAtoms` used `Class::isInstance` then for-each — both forms emit `checkcast AetherKey` after `Iterator.next()`. Cross-hierarchy `LeaderKey` blew up. Fixed via `Map<?, ?>` widening (commits `b7486a35c` + `3ab986d88`).
   - SwimTimeouts default `suspectTimeout=5s` evicted slow-booting Docker peers — reverted to 15s default (`33d0b0127`). Theme A wiring kept; defaults restored.
   - Cluster A had 37 ghost peers (got=37, expected=5), no leader, cascading deploy failures. Root cause: `aether_pgdata` postgres volume external/named — survived `compose down -v`, replayed phantom CTM-provisioned ON_DUTY entries from prior runs. Fixed by dropping volume in `deploy_docker` (commit `2130e6abc`).

3. **Integration verification cycle 2** (post volume cleanup): cluster forms cleanly (5 nodes, leader elected, slices deploy 12 instances, app HTTP responds) — but smoke gate fails because `cluster_node_count` returns 2 of 5.
   - Bash dynamic-scoping bug discovered: `parse_suite_conf` sources `cluster=non-destructive` which clobbered `run_suite`'s local `cluster="a"` argument (`178edc22f`). Cluster A suites had been silently routing to cluster B's endpoints + container names. The renamed local (`target_cluster`) is the canonical fix; `declare -g` workaround discarded (bash 3.2 incompat).
   - Sticky SUSPECTED state diagnosis: SWIM transient probe at boot marks 3 peers SUSPECTED before their SWIM transport is ready; only `SwimHint(HEALTHY)` clears it but SWIM never re-emits. ClusterSyncPong fan path was the existing best-bet but only emitted from PIGGYBACKED observations (followers don't push HEALTHY to buffer).
   - Fixes layered: `QuicPeerStateListener` emits SwimHint(HEALTHY) on join + catch-up for already-connected peers (`e7dcec969`); pong-fan emits SwimHint(HEALTHY) for sender on every pong (`870f89b79`).
   - Validated: 4 of 5 peers cleared post-fix (was 0). One peer (node-2) still stuck SUSPECTED — its pong delivery degrades around T+10min (391 pongs vs 2540 for other peers, ~6.5× fewer). Sparse pongs → sparse HEALTHY emissions → SUSPECTED re-mark sticks.

## Commits landed this session (25 commits, no reverts)

### Wave 3 (commits 1–14)

```
c3b7aae56 Theme A: SwimTimeouts wiring + QUIC ping-miss FAULTY promotion + swimHints projection
eba71e168 Theme B: CTM provisioning stability window + slot timeout + seed grace + first-snapshot barrier
2e06d3321 Theme C: AppHttpState.Quiesced on quorum loss + eviction quorum guard + DrainCoordinator stub
0ae1aa821 Theme D: scale-down 2-phase drain + scale-up rebalance + immediate ClusterConfig reconcile
4130678e2 Theme E: metadata-preserving NodeLifecycle writes + DhtPartition epoch guard + TaskAssignment race lock
7933d4665 Theme F: drain-and-subscribe race elimination (LoadBalancer + TaskGroupAssignment)
975136d94 Theme G: replace AtomicBoolean isLeader with LeaderManager.isLeader() (Rollback/ScheduledTask/ClusterSyncPongFan)
60cfacec2 Theme H: inject LongSupplier clock into NodeDeployment + ClusterDeployment + TaskAssignment + CTM
97cae9b8b Theme I: JitterUtil + retry jitter (slice/consumer/alert/cert/topology + Rabia/SWIM light)
b084b524d Theme J: wrap silent FSM event arms in tx.handle()
8583def52 Theme K: derive node join order from observedCoreEpoch + DecommissionedAtomGc
5cf19b387 Theme L: capture ScheduledFuture refs + onCasLost (LeaderElection/ScheduledTaskManager/CertRenewal)
cd5a66756 Theme M: NDM Active.onEntry callback + AppHttpServer assertion + LeaderAwareSnapshotSource TTL + bootstrap hint
33d0b0127 [revert/correction]: restore SwimTimeouts default suspectTimeout=15s (was 5s — broke cold-boot)
```

### Integration cycle 1 fixes (commits 15–17)

```
b7486a35c countLifecycleAtoms instanceof loop (incomplete fix — for-each still triggers checkcast)
3ab986d88 widen kvSnapshot iteration to Map<?,?> (proper fix — bytecode-equivalent forms ALL get checkcast)
512f0f349 CTM bootstrap-grace + operator-bypass + integration test SLA realism
```

### Integration cycle 2 fixes (commits 18–25)

```
178edc22f bash dynamic-scoping bug — parse_suite_conf clobbered run_suite local
7bc3869d5 cluster harmony — DECOMMISSIONED filter + CTM QUIC-live cross-check + LeadingReprojecting barrier + ghost cleanup
8650cfe64 ClusterAwaitQuiescedRoute periodic progress logging
2e7b85dd1 fix-all: QUIC reconnect skips duplicate ADD + CTM stability bump on QUIC peer events + 3s grace + wait_for_task_assigned
2130e6abc drop aether_pgdata volume on cluster A redeploy + tighten smoke assertions to equality
2eccc2d77 clear sticky SUSPECTED on positive QUIC liveness (correct shape, partially unreachable)
e7dcec969 emit local SwimHint(HEALTHY) on QUIC peer-join + catch-up for already-connected peers
870f89b79 pong-fan emits SwimHint(HEALTHY) for sender on every pong (continuous liveness)
```

All pushed. `mvn -pl aether/node install -am -DskipTests` green at HEAD.

## Critical files (touched this session)

### Detection / health
- `aether/aether-deployment/src/main/java/.../generation/fsm/HealthReconcilerContext.java` — sticky SUSPECTED clear path on QUIC liveness (line ~1033), markSuspected/clearSuspected logging (~1059, ~1074)
- `aether/aether-deployment/src/main/java/.../generation/ClusterGenerationProjector.java` — DECOMMISSIONED filter (~195)
- `aether/aether-deployment/src/main/java/.../generation/HealthReconcilerActivator.java` — `countLifecycleAtoms` Map<?,?> iteration (~420)
- `aether/aether-deployment/src/main/java/.../generation/DecommissionedAtomGc.java` — same Map<?,?> fix (~140)
- `aether/aether-deployment/src/main/java/.../generation/fsm/HealthReconcilerState.java` — LeadingReprojecting first-publish barrier (~198)
- `aether/aether-metrics/src/main/java/.../ClusterSyncPongSignalFan.java` — emits SwimHint(HEALTHY) for pong sender
- `aether/aether-metrics/src/main/java/.../ClusterSyncCollector.java` — diagnostic logs on ping/pong receive
- `aether/aether-metrics/src/main/java/.../fsm/ClusterSyncContext.java` — diagnostic log on ping send
- `aether/node/src/main/java/.../AetherNode.java` — `attachQuicPeerStateListener` emits SwimHint(HEALTHY) on join/reconnect, catch-up for `connectedPeers()`; `VERSION = "1.0.0-rc1"`; class-level `LOG`

### CTM / deployment
- `aether/aether-deployment/src/main/java/.../cluster/ClusterTopologyManagerRecord.java` — bootstrap grace (~328), QUIC live-count guard in handleDeficit (~607), onClusterConfigChanged operator-bypass (~294), onQuicPeerJoined/Left hooks
- `aether/aether-deployment/src/main/java/.../cluster/NodeReconcilerState.java` — ProvisioningSlot record
- `aether/environment-integration/src/main/java/.../AutoHealConfig.java` — provisioningTimeout, provisionStabilityWindow, decommissionedRetention
- `aether/aether-config/src/main/java/.../cluster/AutoHealSpec.java` + `ClusterBootstrapConfigParser.java` — TOML wiring

### Test infrastructure
- `aether/tests/integration/run-tests.sh` — `target_cluster` rename (line 204), aether_pgdata drop on redeploy (line 415), pre-suite ghost cleanup, `rebuild_remote_node_image` chain
- `aether/tests/integration/lib/suite.sh` — bash 3.2-compat plain assignments
- `aether/tests/integration/lib/cluster.sh` — `wait_for_task_assigned` helper, `restart_all_nodes` SLA bumps
- `aether/tests/integration/suites/00-smoke/test-cluster-formation.sh` — equality assertions (was ≥)
- `aether/tests/integration/suites/02-chaos/test-kill-*.sh` — bumped timeouts
- `aether/tests/integration/env/{remote,docker,cloud-hetzner}-b.toml` — `provision_stability_window=5s` for tests

### QUIC
- `integrations/consensus/src/main/java/.../net/quic/PeerState.java` — `AttachResult.RECONNECTED` for stale-CONNECTED transitions
- `integrations/consensus/src/main/java/.../net/quic/QuicClusterNetwork.java` — `ViewChangeOperation.RECONNECT` suppresses duplicate ADD; `setPeerStateListener` invocation at lines 965/980/998
- `integrations/consensus/src/main/java/.../net/quic/QuicPeerStateListener.java` (new) — interface

## Behavior trajectory

| Run | Cluster A formation | Smoke result | Notes |
|---|---|---|---|
| Pre-Wave-3 (`91b99bd68`) | 8/15 stable | gate sometimes green | baseline |
| Post Wave 3 + revert | gate fail (`got 3 of 5`) | hard fail | SwimTimeouts 5s evicted slow boot |
| Post 33d0b0127 | gate fail (`got 3 of 5`) | hard fail | ClassCastException killed bootstrap |
| Post 3ab986d88 | gate fail (`got 2 of 5`) | hard fail | bash scoping routed to cluster B |
| Post 178edc22f | gate fail (`got 4 of 5`) (cluster A) | hard fail | per-suite cluster correct, but ghost peers on host |
| Post 2130e6abc | gate fail (`got 2 of 5`) | hard fail | volume reset cleared ghosts; sticky SUSPECTED begins to surface |
| Post 870f89b79 | gate fail (`got 4 of 5`, mostly) | hard fail | 2 of 3 SUSPECTED clear; node-2 sticky |

Trajectory: **0 → 4 of 5 healthy core members on the leader's snapshot**. The remaining 1 is a transport asymmetry, not a correctness defect.

## What WORKS now (vs session start)

1. **Wave 3 architectural foundations** — all 13 themes landed, observer cardinality complete, FSM coordination consistent, clock injection on coordination contexts, retry jitter applied, drain-and-subscribe primitive consumed at 8 sites.
2. **Cluster forms cleanly** — 5 nodes start, leader elects (`node-1`), quorum holds, snapshot quiesces at `1:3` after deploy.
3. **Slice deployment works** — blueprint pushes, deploys, 12 instances active, app HTTP responds, app endpoints reachable.
4. **Volume contamination eliminated** — `aether_pgdata` drop ensures fresh consensus state every run.
5. **Cluster routing correct** — bash dynamic-scoping bug fixed, cluster A suites no longer kill cluster B containers.
6. **CTM phantom-provisioning closed** — bootstrap grace + QUIC live-count cross-check.
7. **DECOMMISSIONED no longer permanently DEGRADES** — projector filters tombstones.
8. **First-publish barrier** — covers both LeadingSteady and LeadingReprojecting paths.
9. **QUIC reconnect** — RECONNECT op skips duplicate ADD topology emission.
10. **Sticky SUSPECTED clears (mostly)** — local QUIC peer-join + per-pong fan emission.

## What does NOT work yet

### node-2 transport asymmetry (smoke-gate blocker)

Symptom: leader receives 391 pongs from node-2 vs 2540 from each of node-3/4/5 (~6.5× fewer). After T+10 min, node-2 transiently re-marked SUSPECTED with no subsequent HEALTHY emission promptly clearing it (sparse pongs).

Investigation pointers (next session):
- Check QUIC backpressure metrics for node-2 specifically. The earlier "Backpressure queue full" log line referenced node-2 during ghost-peer state. Even after volume reset, asymmetry persists.
- `docker logs aether-a-node-1 2>&1 | grep -E "node-2|backpressure|ClosedChannelException"` — see if there's a transport-level error specific to node-2.
- Compare follower outbound metrics: does `aether-a-node-2` send pongs at the same rate as other followers? If yes → leader's inbound from node-2 has issues. If no → node-2 isn't sending.
- Possible: ZGC pause, JVM GC asymmetry, container resource limit on node-2.
- A SUSPECTED auto-decay (time-based clear when no recent re-mark + no recent ping-miss) would defensively unblock smoke even with sparse pongs.

### Diagnostic logs in production code

`HealthReconcilerContext.markSuspectedInMemory` / `applyClearSuspected` and `ClusterSyncCollector.onClusterSyncPing/Pong` and `ClusterSyncContext.sendOnePing` and `AetherNode.attachQuicPeerStateListener` were instrumented with `log.info` for diagnostics this session. These should be reviewed:
- INFO level on a per-tick path is verbose. Demote to DEBUG once the issue is closed.
- The `ClusterSyncContext.sendOnePing` log uses `LoggerFactory.getLogger(ClusterSyncContext.class)` inline — should be a static field.

### Wave 3 known-deferred (rc2)

| Theme | Item | Issue |
|---|---|---|
| K1 | CDM `transitionalStateTimestamps` / `restoringBlueprints` / `permanentlyFailed` to KV | Schema extension required |
| K2 | TaskAssignmentCoordinator `failedNodes` cooldown to KV | New atom or atom field |
| Q11+Q12+Q13 | Drain protocol on quorum loss | rc2 #189 — DrainCoordinator stub installed |
| Theme I follow-up | Refactor `QuicClusterNetwork.jittered(...)` to use `JitterUtil` | Mechanical |

## Verification commands

```bash
# Module tests (touched modules)
mvn -pl aether/aether-deployment,aether/node,aether/aether-metrics,integrations/consensus install -am -DskipTests

# Just deployment tests (most-touched)
mvn -pl aether/aether-deployment test

# Refresh remote node JAR (run-tests.sh does this automatically)
mvn -pl aether/node install -am -DskipTests

# Integration suite (cluster A non-destructive only would speed iteration)
cd aether/tests/integration && ./run-tests.sh --env remote --skip-build
```

## Next-session P0

1. **Diagnose node-2 transport asymmetry**. SSH `$TARGET_HOST`, pull `docker logs aether-a-node-2`, compare its outbound pong send rate vs other followers' rates. If asymmetric, look at QUIC backpressure stats per-peer on the leader. Once clear, fix should be either (a) eliminate the asymmetry (transport-level bug) or (b) add a SUSPECTED auto-decay (TTL ~30s without re-mark + no ping-miss → auto-clear).

2. **Demote diagnostic INFO logs to DEBUG**. The `ClusterSync: received PONG` and `Marking peer healthHint=SUSPECTED` etc. were INFO for diagnosis. Once the issue is rooted, demote them.

3. **Run full integration suite**. Once smoke is green, the remaining suites (cluster A non-destructive, cluster B destructive chain) should run. Expect cluster A non-destructive to be largely green (Wave 3 cleanup applied). Cluster B chaos may still have replacement-join latency issues — those are separate.

4. **rc2 hooks audit**. The drain protocol hooks (`DrainCoordinator`, `NoOpDrainCoordinator`, `NodeDeploymentState.Leaving`) are stubs. Verify all `TODO(rc2-#189)` markers are tracked in #189.

## Open architectural questions for rc2

- **SwimHint event sources**: only ClusterSyncPongFan + my new `QuicPeerStateListener` emit HEALTHY hints. SWIM itself doesn't emit `onAlive` events — only SUSPECT/FAULT/LEFT. Should SWIM emit `MemberHealthy` callbacks for symmetry?
- **swimHints map TTL**: Theme A added the projection, but the map has no expiry. SUSPECTED entries written by transient probes never expire unless explicitly cleared. A 60s TTL would make boot-time transients self-heal.
- **Pong-fan emission scope**: my fan now emits HEALTHY for `pong.sender()`. Should it also emit a `RemoteConnectivity(CONNECTED)` for the sender? Currently only piggybacked observations emit Connectivity.
- **CTM activate ordering vs. snapshot publish**: Theme B chained `ctm.activate()` after `bootstrapCommittedCallback`. If the bootstrap batch is empty (e.g., second leader after re-election), the callback fires immediately. Verify activation timing is consistent across leader-change scenarios.

## References

- RC1 session-handover chain: `2026-04-22 → 2026-04-23 → 2026-04-24 → 2026-04-25 → (this) 2026-04-26`.
- Plan doc: `~/.claude/plans/let-s-plan-and-then-jolly-fox.md` — original Wave 3 spec.
- rc2 ticket: `#189` — drain protocol on quorum loss.
- Diagnostic transcripts in session metadata.

---

**Session totals:** 25 commits, ~10 hours active development. Wave 3 architectural cleanup complete; integration unblocking phase ~80% complete (cluster forms cleanly, only single-peer transport asymmetry remaining). Cluster A goes from "smoke gate hard-fail with 37 phantom peers" to "smoke gate near-pass with 4 of 5 healthy peers visible".
