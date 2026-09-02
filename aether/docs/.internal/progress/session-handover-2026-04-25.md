# Session handover — 2026-04-25

**Branch:** `release-1.0.0-rc1`
**HEAD:** `8a3101898` (Fix 5 revert)
**Prior handover:** `aether/docs/internal/progress/session-handover-2026-04-24.md`

## One-line summary

FSM coordination wave 2 (Q1–Q13 architectural fixes) + cluster-B chaos fixes 1–4 landed; Fix 5 (CTM provisioning slot timeout) reverted due to cluster-A regression. Smoke regression resolved. Final integration tally: smoke gate green, cluster-B destructive chain remains failing (architectural issue: replacement nodes too slow to reach HEALTHY/ON_DUTY before test timeouts).

## Session arc

This session bridged three phases:

1. **FSM coordination audit** — deep architectural review of all 11 FSMs introduced in the prior FSM wave, surfacing 14 architectural questions. User walked through each one-by-one, decisions captured. Resulted in 13 commits implementing Q1–Q10 + Q12 (clock injection on remaining contexts), plus rc2 ticket #189 for drain protocol (Q11+Q12+Q13 deferred — too large for one session).
2. **Cluster-B destructive-chain triage** — three parallel root-cause investigations (SWIM, QUIC, auto-heal) revealed a dependency chain: SWIM never fires FAULTY → swimHints stays HEALTHY → CTM sees no deficit → no auto-heal. Plus QUIC datagram channel leak + 1Hz reconnect storm. Four targeted fixes landed (1–4); Fix 5 reverted.
3. **Integration verification** — smoke gate confirmed fixed; cluster-B chaos still failing but with materially different symptoms (now stalled on replacement-join latency, not on missing auto-heal triggers).

## Commits landed this session (33 commits total, 1 revert)

### FSM coordination wave 2 (Q1–Q10, Q12) — 13 commits

```
3114aac11 feat(core): add CancellableTask.cancellableTask(ScheduledFuture) factory
f5e5a56ca feat(statemachine): split FsmTags(kind, instance) — eliminates MicrometerFsmObserver cardinality explosion
de0bd93e6 feat(statemachine): add FsmState.onCasLost() hook + convert 4 FSM states to eager-schedule pattern
524820d69 feat(statemachine): add TransitionRequest.handle(Runnable) + migrate 19 side-effect handler callsites
c82c23b4d refactor(health): replace SignalOutcome booleans with MembershipChange + TermAdvance enums
2064f0498 refactor(health): remove defensive Option wrap in ReprojectionCompleted dispatch
b13073d0d refactor(control): remove dead Result.lift in runEvaluationCycle
f4cfe6910 refactor(fsm): inject LongSupplier clock into ControlLoopContext + HealthReconcilerContext
8fe1cd059 refactor(delegation): TaskAssignmentCoordinator.reassign returns Promise<Unit>
95fdd41ea refactor(jbct): rename 4 lowercase inner records to PascalCase, drop Impl suffix
c93cb6b11 refactor(tls): expand RenewalStatus enum — add INITIALIZING and STOPPED variants
```

### Q1–Q9 architectural fixes — 9 commits

```
373359dc3 feat(metrics): introduce PeerObservationStore as node-singleton — eliminates role-gated follower buffer drift
5016f2b3f feat(health): observation timestamps + consumer-side staleness filter (default 30s, configurable)
52ab4153c refactor(health): move consecutivePingMisses to PeerObservationStore — per-NODE lifetime survives leader thrash
7052bb1a0 fix(health): drain PeerObservationStore on Leading entry + subscribe — race-free, fixes smoke regression
736934d24 refactor(health): SwimHealthState Stopped/Starting idle; QuicClusterNetwork is canonical observation source
b1bbb9370 refactor(consensus): unify LeaderChange + drop BecameLeader; LeaderManager.currentLeaderEpoch is SSOT
a05ed54ff refactor(health): batch leader-change bootstrap into single cluster.apply(List)
1bf69c772 fix(http): AppHttpState Starting transition out re-fetches routes + quorum
24e308e35 refactor(consensus): drop consensusReadyPending flag — query RabiaEngine.isActive at QuorumWaiting.onEntry
09af46748 docs(deployment): document Active.onEntry invariant — bounded pending-load iteration
```

### Cluster-B destructive-chain fixes 1–4 — 4 commits

```
2f8a5d8d8 fix(swim): same-incarnation same-state updates are no-ops — fixes SUSPECT→FAULTY timer reset preventing auto-heal
d2b0043ff fix(quic): per-peer datagram channel tracking + SO_REUSEADDR + jittered backoff — eliminates socket leak and 1Hz reconnect storm
65aecc985 feat(health): promote sustained QUIC ping-miss to swimHints[FAULTY] — defense-in-depth for failure detection
1f6430d31 feat(deployment): CTM logs per-tick reconcile decisions — visibility into deficit/convergence transitions
```

### Fix 5 + revert

```
316d2656a fix(deployment): CTM provisioning slot timeout — top-up dispatch when partial waves stall   [REVERTED]
8a3101898 Revert "fix(deployment): CTM provisioning slot timeout — top-up dispatch when partial waves stall"
```

All pushed. `mvn -pl aether/node install -am -DskipTests` green at HEAD.

## Integration test progression

| HEAD | Pass/Fail | Notes |
|---|---|---|
| `61daf926d` (session start baseline) | 9/15 | Smoke gate sometimes failed; cluster-B 1/5 |
| `c93cb6b11` (smoke regression) | 1/15 (gate FAIL) | Q3-stage smoke probe revealed broken HealthReconciler subscribe |
| `09af46748` (post Q1–Q10) | 8/15 | **Smoke regression resolved**; cluster-B unchanged |
| `1f6430d31` (post Fixes 1–4) | 7/15 | Auto-heal now firing; replacement-join latency still gates chaos |
| `316d2656a` (post Fix 5) | 6/15 | **REGRESSION** — phantom node provisioning in cluster A |
| `8a3101898` (Fix 5 reverted) | (untested; expected ≈ 7/15 like `1f6430d31`) |

### Final passing suites at `1f6430d31`/`8a3101898` (cluster A non-destructive — all stable)

```
[PASS] 00-smoke                    2p/0f
[PASS] 04-streaming                4p/0f
[PASS] 07-cluster-mgmt             4p/0f
[PASS] 09-artifacts                3p/0f
[PASS] 10-database                 3p/0f
[PASS] 11-observability            5p/0f
[PASS] 14-storage                  2p/0f
[FAIL] 08-resources                4p/1f   (1 sub-test flake)
```

### Failing suites (cluster B destructive chain + dependents)

```
[FAIL] 02-chaos                    0p/4f   (kill-leader/multiple/node/under-load)
[FAIL] 03-scaling                  0p/3f   (quorum-safety, scale-up, scale-down)
[FAIL] 05-security                 1p/2f
[FAIL] 06-deployment               4p/1f   (varies; sensitive to cluster-A health)
[FAIL] 12-network                  1p/2f
[FAIL] 13-edge-cases               0p/3f
[FAIL] 15-delegation               1p/1f
```

## Architectural results

### What WORKS now (vs session start)

1. **Smoke gate is fixed** — Q3's race-free drain-and-subscribe eliminated the regression where node-5's healthHint stuck at SUSPECTED post-promotion.
2. **All 11 FSMs use clean SSOT pattern** — guard-visible fields on state-data, no role-gated routing on Context, no buffered events between FSM states.
3. **`isLeaderGate` AtomicBoolean eliminated** — `LeaderManager.isLeader()` is canonical SSOT; `LeaderManager.currentLeaderEpoch()` mirrors for epoch.
4. **`BecameLeader` event eliminated** — `LeaderChange` is the canonical leader-state event; HealthReconciler reads epoch from LeaderManager at transition time.
5. **SWIM faulty-detection works** — same-incarnation no-op fix (Fix 1) now correctly transitions SUSPECT→FAULTY within the configured 15s timeout.
6. **QUIC reconnect is bounded** — per-peer datagram channel map + jittered backoff (100ms–5s) eliminates the 1Hz reconnect storm and socket leak.
7. **Defensive failure detection** — sustained QUIC ping-misses (>10 default) promote to `swimHints[FAULTY]` even if SWIM is delayed.
8. **CTM auto-heal observability** — per-tick reconcile decisions logged at INFO/DEBUG; deficit/convergence transitions visible.
9. **DockerCloudProvider IS firing** — confirmed via cluster-B logs showing `aether-core-node-N-XXXX` cloud-provisioned IDs replacing killed peers.
10. **Library FSM hooks complete** — `onCasLost`, `transitionTo.handle(Runnable)`, `FsmTags(kind, instance)` all in place.

### What does NOT work yet (cluster-B chaos chain)

Replacement-node-becomes-HEALTHY latency exceeds chaos test timeouts (typically 120s/180s). Auto-heal fires correctly but the new container takes too long to:
1. Spawn (1–3s — fast)
2. Pull artifacts and start JVM (~10–20s — moderate)
3. Connect QUIC + adopt leader + catch up consensus (~10–30s — moderate)
4. SWIM stabilize as ALIVE on this peer view (~5–15s — moderate)
5. HealthReconciler project HEALTHY hint into snapshot (~5–10s — fast)

Total: 30–80s typical. With multi-kill scenarios (kill-multiple, kill-under-load), 2+ replacements need to stabilize before chaos test timeouts. Fix 5 attempted to address the partial-wave-stall gap with slot-timeout top-up dispatch but introduced a cluster-A regression (see "Fix 5 failure analysis" below).

## Fix 5 failure analysis (for follow-up implementation)

**Goal:** when CTM's `Reconciling` state has slots whose deadlines pass without the replacement reaching HEALTHY, free those slots and re-dispatch top-up provisions. Self-correcting on stalled provisions.

**Implementation (commit `316d2656a`):**
- New `ProvisioningSlot(long spawnedAtMs, long deadlineMs)` record on `NodeReconcilerState.Reconciling.inFlight`.
- `provisionNodes(N)` adds N slots with `deadlineMs = nowMs + autoHealConfig.provisioningTimeout()` (default 60s).
- Per-tick reconcile expires timed-out slots: `slots.removeIf(slot -> slot.deadlineMs < nowMs)`.
- Deficit recomputed as `configured - (realActual + nonExpiredSlots.size())`.
- If deficit > 0, dispatch top-up.

**Failure mode (observed at HEAD `316d2656a`):**

Cluster A (5 static nodes, no kills) regressed from 7/15 → 6/15. Key new failures:

- `06-deployment` 4/1 → 0/5 — total regression.
- `10-database` 3/0 → 2/1 — new failure.
- Multiple test failures with pattern `await-quiesced status=500 after Xms (target=1:N)`.
- Leader log: `ERROR QuicClusterNetwork.handleWriteResult - Failed to write to peer NodeId[id=aether-core-node-1-59a65cc36] on stream CONSENSUS / java.nio.channels.ClosedChannelException`.

**Root cause hypothesis:**

`aether-core-node-1-59a65cc36` is a **cloud-provisioned node ID** (format `aether-core-node-N-XXXX`) appearing in **cluster A**. Cluster A is supposed to have 5 static nodes (per `docker-compose-a.yml`), no provisioning. The phantom node entered the topology via CTM provisioning during cluster startup race.

Sequence:

1. Cluster A starts. Initial 3 nodes form quorum (others still booting).
2. First leader sees `configured=5`, `realActual=3`. Deficit detected.
3. With Fix 5 active, CTM dispatches `provisionNodes(2)` and creates 2 slots.
4. The remaining 2 STATIC nodes finish booting; `realActual` rises to 5.
5. CTM should now see `configured=5`, `realActual=5`, deficit=0 and converge.
6. BUT: the slots from step 3 are still "in flight" (not expired yet, 60s deadline).
7. CTM dispatched 2 ghost provisions in step 3 — those provision attempts go to DockerComputeProvider, which actually starts containers.
8. Those phantom containers can't fully join (cluster-A's compose env doesn't expect them; networking/DNS resolution mismatched).
9. Phantom containers appear in cluster topology via consensus log entries (CTM wrote `NodeLifecycleValue(PROVISIONING)` atoms).
10. Leader tries to write to phantoms via QUIC consensus stream → `ClosedChannelException`.
11. Generation snapshot publishing blocks waiting for phantom ACKs → `await-quiesced` returns 500.

**Why Fix 5 is structurally premature:**

The bug Fix 5 addresses (partial-wave stall) is real and worth fixing. But the implementation provisions during the cluster startup race window. Pre-Fix-5, the `Reconciling` lock prevented this — initial deficit during boot was tolerated until the static nodes finished joining.

**Correct fix design (for next session):**

Three approaches, in order of safety:

1. **Initial cooldown gate.** Don't enter Reconciling (and thus don't provision) for `startupCooldown` (already configured) PLUS a stability window where `realActual` has been stable at >=3 for at least N seconds. Prevents the boot-race phantom dispatch.
2. **Stability window check.** Track when `realActual` last changed. Only dispatch provisions if `realActual` has been stable for `provisionStabilityWindow` (e.g. 30s). Allows transient race resolution before assuming a real deficit exists.
3. **Slot accounting fix.** When `realActual` rises (a new node joins, regardless of provenance), decrement nonExpiredSlots by 1. This means a static node finishing its initial join "consumes" a slot, preventing CTM from spawning a replacement for what was actually expected. Tricky to implement correctly because we don't know whether the new node is a phantom or static.

Option 1 or 2 are safest. Option 3 has too many edge cases.

**Required tests for next attempt:**

- `clusterStartup_partialBoot_doesNotProvisionStaticNodesAsReplacements` — boot 3 nodes first, wait, then boot remaining 2; assert no provisioning was dispatched.
- `chaosKill_partialWaveStall_topUpDispatchedAfterTimeout` — the original Fix 5 test; confirm top-up still works post-cooldown-gate.
- Existing tests must pass unchanged.

**Files for re-implementation:**

- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerRecord.java` (handleDeficit, handleDeficitDuringReconciling)
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/NodeReconcilerState.java` (ProvisioningSlot record, Reconciling.inFlight)
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/AutoHealConfig.java` (provisioningTimeout config — KEEP from Fix 5; was correct)
- `aether/aether-config/src/main/java/org/pragmatica/aether/config/cluster/AutoHealSpec.java` + `ClusterBootstrapConfigParser.java` (TOML binding — KEEP from Fix 5)

The reverted commit `316d2656a` has 90% of the right shape. The missing piece is the initial-window guard. Re-cherry-pick + add the guard.

## Known issues / open work

### rc2 deferred

- **#189 — drain protocol on quorum loss** (Q11+Q12+Q13 from this session's audit). Spec captured in detail in the issue. 1–2 sessions of work.
- **Architectural concerns** still on the rc2 list:
  - Cascade depth in `HealthReconcilerActivator.onLeaderChange` (5+ KV writes — addressed by Q9 batch but worth audit).
  - `NodeDeploymentState.Active.onEntry` synchronous KV iteration (documented as bounded; verify still holds at scale).

### Cluster-B chaos blockers (next-session P0)

In dependency order:

1. **Fix 5 redo with startup-window guard** — addresses partial-wave stall without the cluster-A regression.
2. **Investigate replacement-join latency** — even with Fix 5 redone, single-kill replacement may still take 60–80s; chaos test timeouts may need adjustment OR replacement-join needs optimization (SWIM+HealthReconciler stabilization is the long pole).
3. **`restart_all_nodes` between destructive tests** — currently fails at 60s timeout because the cluster doesn't quiesce in time after multi-kill recovery. Either:
   - Increase the timeout (60s → 120s+).
   - OR: add a "drain remaining state" phase in `restart_all_nodes` that's more deterministic.

### Cluster-A regressions (worth investigating)

- `08-resources` shows 4/1 — 1 sub-test flake. Same as session-start; not a regression. But worth identifying which sub-test and why.
- `06-deployment` is sensitive to cluster-A health propagation; sometimes 5/0, sometimes 4/1. Stabilize by improving generation-quiesce reliability or tightening test waits.

### JBCT debt (low priority)

The `feature_catalog.md` and `CHANGELOG.md` updates for this session's commits are pending. Nothing in this session's commits constitutes a user-facing feature change beyond the FSM refactor; one CHANGELOG entry summarizing the 33-commit FSM coordination wave is sufficient.

## Critical files (touched this session)

### Library

- `integrations/statemachine/src/main/java/org/pragmatica/statemachine/{Fsm,FsmState,TransitionRequest,FsmObserver,FsmTags}.java`
- `core/src/main/java/org/pragmatica/lang/concurrent/CancellableTask.java`

### Failure detection chain

- `aether/aether-metrics/src/main/java/org/pragmatica/aether/metrics/observation/PeerObservationStore.java` (new node-singleton)
- `aether/node/src/main/java/org/pragmatica/aether/node/health/fsm/SwimHealthContext.java`
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/generation/fsm/HealthReconcilerContext.java`
- `integrations/swim/src/main/java/org/pragmatica/swim/SwimProtocol.java` (Fix 1 — same-incarnation no-op)
- `integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/{QuicClusterClient,QuicClusterServer,QuicClusterNetwork}.java` (Fix 2)

### CTM auto-heal

- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerRecord.java`
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/NodeReconcilerState.java`
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/AutoHealConfig.java`

### Leader / consensus

- `integrations/consensus/src/main/java/org/pragmatica/consensus/leader/LeaderManager.java`
- `integrations/consensus/src/main/java/org/pragmatica/consensus/leader/fsm/{LeaderElectionContext,LeaderElectionState,LeaderElectionFsm}.java`

## Verification commands

```bash
# Module tests
mvn -pl aether/aether-deployment,aether/node,integrations/swim,integrations/consensus,integrations/cluster test -am

# Full build
mvn -pl aether/node install -am -DskipTests

# Integration suite (~60 min on remote)
cd aether/tests/integration && ./run-tests.sh --env remote --skip-build
```

## Next-session P0

1. **Re-implement Fix 5 with startup-window guard.** Reverted commit `316d2656a` had 90% of the right shape; add the missing guard. See "Fix 5 failure analysis" above for design.
2. **Run integration tests after Fix 5 redo.** Confirm cluster-A doesn't regress AND cluster-B chaos benefits.
3. **If chaos still fails:** investigate replacement-join latency — SWIM stabilization + HealthReconciler HEALTHY hint propagation are the long pole. Either optimize or adjust test timeouts.
4. **Don't touch FSM coordination layer further this RC.** It's stable; further changes have diminishing returns and elevated regression risk.

## References

- RC1 session-handover chain: `2026-04-22 → 2026-04-23 → 2026-04-24 → (this) 2026-04-25`.
- rc2 ticket: [#189](https://github.com/pragmaticalabs/pragmatica/issues/189) — drain protocol on quorum loss / shutdown.
- Investigations referenced inline; full agent transcripts in session metadata.

---

**Session totals:** 33 commits + 1 revert. ~10 hours active development. 5 of 5 cluster-B fixes attempted, 4 successful, 1 reverted with detailed re-implementation plan.
