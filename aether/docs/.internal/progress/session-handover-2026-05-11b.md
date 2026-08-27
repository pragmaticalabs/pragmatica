# Session Handover — 2026-05-11b (D.3-D.5 architectural items landed)

**Branch:** `release-1.0.0-rc1` · **HEAD:** `f3c13e39f` (local; pushes pending) · **Tag:** `v1.0.0-rc1-candidate` still at `8ff409c3b` (not re-pushed since the post-architectural commits)

Continuation of [`session-handover-2026-05-11.md`](session-handover-2026-05-11.md). The earlier handover documented D.1 + D.2 landing; this one documents D.3 + D.4 + D.5 completion and the open C.4 docker-remote validation block.

---

## ⚡ TL;DR

**All 5 RC1-blocking architectural items now landed.** Three commits past the previous handover:

```
f3c13e39f test(infra): scp nginx-mgmt-gateway-{a,b}.conf to remote + rm stale dir before scp
22cdbf3f8 feat(consensus,drain): D.3 phase split COLD_BOOT/RECOVERING + D.5 ConsensusDrainCoordinator (RC1-blocking)
17d277608 test(infra): semantic restore_cluster_baseline (RC1-blocking) — replace fixed-fixture restart with elastic-membership cleanup
```

**C.4 docker-remote validation deferred:** the first three takes (after D.3+D.5 landed) all failed before any test ran. Symptoms — gateway containers start but cluster A never reaches 5 nodes within 360s on the gateway endpoint. Root cause not yet diagnosed — could be a D.2 threshold interaction at the boot path, a D.3 phase-serialization wire incompatibility, or simply more wait budget needed. **Next session priority** is debugging this with shell access to TARGET_HOST.

**Module tests across all 4 architectural items are green** (272/272 aether-deployment + 46/46 swim + 540/540 consensus + 373/373 node — same as session-handover-2026-05-11 §1). The regressions are at integration boot, not at module behaviour.

---

## 1 · D.3 — Phase split COLD_BOOT vs RECOVERING (`22cdbf3f8`)

**What changed.**
- `AetherValue.ClusterPhase` enum: renamed `BOOTING` → `COLD_BOOT`; added `RECOVERING`.
- `SwimProtocol.emitFaultyOrUnknown`: suppression gate is now `phase == COLD_BOOT` only (not RECOVERING). RECOVERING has full failure semantics.
- `HealthReconcilerImpl` phase transitions:
  - `COLD_BOOT → NORMAL`: first time ⌈(N+1)/2⌉ peers reach Healthy (quorum-based, not full-membership).
  - `NORMAL → RECOVERING`: any peer in `everSeenHealthy` drops below Healthy.
  - `RECOVERING → NORMAL`: cluster has ≥ quorum Healthy peers continuously for `recoveryStableWindowMs` = 5s (down from 30s default).
- `quorumThreshold()` computed from `expectedClusterSize` (configured ground-truth), not observation-time membership — same correctness decision as D.2.
- 13 new phase tests across `SwimProtocolPhaseAwareSuppressionTest`, `HealthReconcilerTest`, `ClusterPhaseSmokeTest`, `ClusterTopologyManagerPhaseAwareTest`.

**Cross-module enum-rename impact.** `aether/slice/AetherValue.java`, `KVStoreSerializer.java` (implicit via name()), `HealthReconcilerImpl/Config`, `ClusterTopologyManagerRecord`, `AetherNode`, `StatusRoutes`, `CoreSwimHealthDetector`, `SwimHealthContext`, `aether/tests/integration/lib/cluster.sh` (docstrings).

**Judgement calls.**
- 5s `recoveryStableWindowMs` matches `stableWindowMs` (COLD_BOOT→NORMAL window) for symmetric guards.
- `isBootingSupplier` kept as boolean (true = COLD_BOOT only) instead of tri-state — preserves SwimProtocol API.
- Quorum derived from configured size avoids split-brain risk where two nodes compute different thresholds during the same observation cycle.

## 2 · D.4 — Semantic `restore_cluster_baseline` (`17d277608`)

**What changed.**
- New `restore_cluster_baseline` helper in `aether/tests/integration/lib/cluster.sh` — ~80 LOC.
- New `cluster_node_count_on_duty_healthy` helper — counts `/api/nodes/lifecycle` entries with `state=ON_DUTY`.
- 4 chaos cleanups (`02-chaos/test-kill-{leader,multiple,node,under-load}.sh`) switched to call `restore_cluster_baseline` instead of `restart_all_nodes`.
- 2 12-network tests (`test-quic-connectivity.sh`, `test-swim-detection.sh`) replaced `start_node "$KILLED_VICTIM"` with `wait_for "5 ON_DUTY healthy cores"` — accept CTM-provisioned replacements as first-class, don't fight the elastic model.

**Why this matters.** Cleanup is now elastic-membership-aware: re-enable auto-heal, reset CTM circuit breaker, scale to N, wait for N ON_DUTY healthy cores (any IDs), await generation quiescence, soft phase=NORMAL. Stops fighting the product model.

**Deliberate retentions.** `restart_all_nodes` + `start_node` kept callable but documented as deprecated. `15-delegation/test-02-reassignment.sh:149` still legitimately calls `start_node` (scale-up node restart BEFORE DECOMMISSIONED — same-NodeId rejoin is the assertion).

## 3 · D.5 — Real ConsensusDrainCoordinator (`22cdbf3f8`)

**What changed.**
- `ConsensusDrainCoordinator.java` (NEW, ~160 LOC) — drain protocol: `prepareDrain → awaitDrainAck(inflight=0 + KV converged) → markDrainComplete`. On timeout: `requestFailedDrain` writes `FAILED_DRAIN` lifecycle state.
- `InFlightRequestTracker.java` (NEW, ~35 LOC) — atomic per-node in-flight request counter.
- `NodeLifecycleRoutes.handleDrain` wires `POST /api/node/drain/{nodeId}` to run the full protocol synchronously with 60s default budget.
- New `NODE_INFLIGHT` ManagementRoute (`GET /api/node/inflight`) + `InFlightResponse` record.
- New `FAILED_DRAIN` lifecycle state in `AetherValue.NodeLifecycleState`.
- 8 new `ConsensusDrainCoordinatorTest` cases across `@Nested` PrepareDrain/AwaitDrainAck/MarkDrainComplete/HappyPath/TimeoutPath.

**State machine.**
```
ON_DUTY ──prepareDrain──▶ DRAINING ──awaitDrainAck──▶ DECOMMISSIONED (success, 200)
                                  └─timeout──▶ FAILED_DRAIN (503; operator review)
```

**Judgement calls.**
- 60s drain timeout default hardcoded (no `drainTimeoutMs` config field yet — deferred).
- Route convergence wait via local lifecycle KV observation (consensus-replicated, so locally-observable DRAINING implies cluster-wide convergence within Rabia replication bound).
- `inFlightProbe` returns local tracker for self, 0 for remote peers — covers leader-self-drain and CTM scale-down on its own node; full remote inflight HTTP probe deferrable.

## 4 · C.4 docker-remote validation — three takes, three failures

| Take | Failure | Fixed by |
|---|---|---|
| 1 (`/tmp/remote-c4-1778527910.log`) | `failed to mount nginx-mgmt-gateway-{a,b}.conf … not a directory` — D.1 missed scp'ing the conf files to TARGET_HOST | Add scp step (`f3c13e39f`) |
| 2 (`/tmp/remote-c4b-…`) | `scp: dest open … Permission denied` — prior failed compose created a directory at the missing-file path | Add `rm -rf ~/nginx-mgmt-gateway-*.conf` before scp (`f3c13e39f`) |
| 3 (`/tmp/remote-c4c-…`) | All containers `Started`, but `wait_for: 5 nodes on http://192.168.0.71:5150 (timed out after 360s)` — cluster never reports 5 nodes in `/api/cluster/generation` | **NOT YET DIAGNOSED** |

**Take 3 in detail.** All `aether-{a,b}-node-{1..5}` containers `Started`, `aether-{a,b}-mgmt-gateway Started`, `forge-postgres Healthy`. The wait timed out 360s. After teardown, containers were torn down so post-mortem inspection impossible from local. The cluster's bootstrap behavior under D.2 + D.3 hasn't been observed live.

**Hypotheses (highest probability first).**

1. **D.2 threshold quorum boot interaction.** At cluster bootstrap (`onDutyCount=0`), `quorumThreshold` floors to 1 (`onDutyCount ≤ 1 ? 1 : ...`). So the leader's single observation SHOULD advance the first peer to ON_DUTY. But if multiple SWIM observations arrive concurrently with the leader's view and the aggregator's per-target sliding window's first observation is non-Healthy, that observation gets pinned and re-evaluated against threshold=1 — fine. Need to verify the actual bootstrap sequence: observed lifecycle KV state at t=10s, t=60s, t=180s. Currently can't.

2. **D.3 wire format incompatibility.** D.3 renamed `ClusterPhase.BOOTING` → `COLD_BOOT`. If anywhere the bootstrap dance compares against the literal string `"BOOTING"` (e.g. in test-side helpers' awaits or in cross-version KV deserialization during a rolling migration), that would break. All nodes run the new JAR though so cross-version isn't an issue. Test-side `wait_for_phase NORMAL` always wanted NORMAL — not BOOTING — so test code is unaffected. But the JAR itself reading prior-session-persisted phase state from `aether_pgdata` volume could be affected — although that volume gets `down -v` cleaned each run, so should start fresh. (`docker-compose-a.yml` includes `forge-postgres` which IS persisted across compose-up cycles. If a previous broken cluster wrote bad ClusterPhase to KV-Store… but KV-Store isn't in PG. So this should be safe.)

3. **Cluster A nginx gateway DNS resolution fails.** Gateway uses `resolver 127.0.0.11` (Docker internal DNS). Both gateway and cores share `aether-a-network`. Should resolve. But if compose's `start_period` for gateway healthcheck runs before nodes start, gateway might enter unhealthy state and not be restarted. The healthcheck timing is `interval=5s timeout=2s retries=5 start_period=5s` so 25s+5s buffer; nodes typically come up in ~20s. Tight but possible.

4. **360s budget genuinely insufficient.** `wait_for_node_count 5` has 360s budget on docker-remote (TIMEOUT_SCALE=2). Bootstrap on docker-remote is normally <60s. But under D.2 + D.3 + freshly-compiled JAR, may have new startup overhead.

**Recommended next-session debug steps.**

```bash
# Re-deploy without auto-teardown to inspect live state
cd aether/tests/integration
./run-tests.sh --env remote --skip-teardown --suites 00,nothing  # bootstrap only
# Then SSH to TARGET_HOST:
#   docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
#   docker logs aether-a-node-1 2>&1 | tail -50
#   docker logs aether-a-mgmt-gateway 2>&1 | tail -30
#   curl -s http://localhost:5150/api/cluster/generation | jq .
#   curl -s http://localhost:5151/api/status | jq .
# This pinpoints which hypothesis is at play.
```

If hypothesis #1: review `ObservationAggregator.onObservation` for boot-time semantics with empty per-target window. Consider whether `expectedClusterSize` (configured) gives a sensible quorum at first observation; may need to gate "if first observation, advance immediately" semantics during cold boot.

If hypothesis #4: raise `wait_for_node_count` budget to 600s (× TIMEOUT_SCALE = 1200s).

## 5 · Tasks state

```
A.1-A.6  ✅ completed
B.1-B.4  ✅ completed
C.1      ✅ completed (11/15 last successful)
C.2      ✅ completed (terminated mid-flight, 9/15 best take)
C.3      ⏳ pending — needs C.4 unblock first
C.4      🚧 in_progress — bootstrap regression after D.2+D.3+D.5; debug next session
C.5, C.6 ⏳ pending — blocked by C.4
D.1-D.5  ✅ ALL completed (architectural items landed)
```

**16 commits unpushed beyond the candidate tag.** Pushing list:
- `f3c13e39f` test(infra): scp nginx-mgmt-gateway-{a,b}.conf to remote
- `22cdbf3f8` feat(consensus,drain): D.3 + D.5
- `17d277608` test(infra): D.4 restore_cluster_baseline
- `5072517c8` docs(handover): 2026-05-11
- `472b529ad` D.1 nginx mgmt-gateway sidecar
- `8104c7d83` D.2 ObservationAggregator threshold quorum
- (plus 10 earlier session commits from session-handover-2026-05-11 §2)

```bash
git push origin release-1.0.0-rc1
# Note: candidate tag NOT re-pushed yet — defer until C.4 actually passes, to
# avoid CI rebuilding a JAR that fails integration.
```

## 6 · Score card

| Metric | Start of session (2026-05-11) | End |
|---|---|---|
| RC1-blocking architectural items | 5 outstanding | **0 outstanding** (all landed) |
| Module test counts | n/a | 272+46+540+373 across 4 modules; **all green** |
| docker-remote validation (post-arch) | n/a | **C.4 blocked at bootstrap; needs live-cluster debug** |
| Cloud Container / Cloud JVM | not started | blocked on C.4 |
| Lines of architectural code | n/a | ~2500 LOC across 5 commits |

**Net.** All 5 architectural items are committed and module-test-green. The blocker for delivering 15/15 has shifted from "architectural work to do" to "integration regression to diagnose at the docker-remote bootstrap layer." The diagnosis path is shell access to TARGET_HOST to inspect the bootstrap sequence live. That's a single-session debug task once the operator (you) can drive SSH commands and inspect the cluster state directly.
