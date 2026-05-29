<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Session Handover — 2026-05-29 — Membership v2: heartbeat-readiness design + Phase A & B1/B2 landed

**Branch:** `release-1.0.0-rc1`. **HEAD:** `868fdd97f`. **Candidate tag:** `v1.0.0-rc1-candidate` → `868fdd97f`. **All work LOCAL/unpushed. Working tree clean, full 67-module chain compiles, all touched-module tests green.**

## 0. TL;DR
Continued the membership-v2 cutover with a **major design advance** (settled interactively with the user) and the first implementation increments. The cutover order was **inverted to orchestration-first** after discovering the old layer is still the *live* membership driver (not "disconnected" as the prior handover assumed). A new **leader↔node control-heartbeat** model replaces both the KV `ON_DUTY` readiness cache and the planned `DrainRequestKey` — readiness is now a node-authoritative state carried on the existing metrics pong; drain is a command on the ping. Spec amended (new §7.5), Phase A done, B1+B2 done.

## 1. Commit chain this session (on `release-1.0.0-rc1`, atop `9bb7182ad`)
```
b22aceaad feat(membership): B4 — CDM allocatable-gate reads leader readiness view (READY peers + self) instead of KV ON_DUTY
973eedfe6 feat(membership): B3 — leader readiness view self-cleans (evict on routed QUIC PeerDisconnected + periodic stale-sweep)
868fdd97f feat(membership): B2 — leader-side readiness view (epoch-fenced pong map + stuck-SYNCING reaper + evict/sweep/snapshot)
dc3c235c3 feat(membership): B1 — node-reported readiness state (SYNCING/READY/DRAINING) + incarnation on heartbeat pong
ea95273df docs(spec): membership v2 — node readiness & drain via leader↔node control heartbeat; remove DrainRequestKey (new §7.5, I13/I14)
584743ebf refactor(membership): CTM v2 state-derived actuators + LeaderReconciler quorum guard; drop slot machinery — E2 phase 2c-α (orchestration-first)
a02fe7842 fix(consensus): update integrations/cluster tests for QuorumStateNotification→ClusterStateNotification rename — completes E2 phase 2c.0
```

### Update — B3 + B4 landed (readiness path FUNCTIONAL end-to-end)
- **B3 (973eedfe6):** leader readiness view self-cleans — `fan.evict` subscribed to the *existing* routed `TransportObservation.PeerDisconnected` (fires on QUIC REMOVE, not RECONNECT-flap; no new message type / no QCN change — the additive, low-risk path the investigation recommended) + a periodic `sweepStale` tick (3×pingInterval) for the QUIC-open-but-silent black-hole case. All additive AetherNode wiring.
- **B4 (b22aceaad):** `ClusterDeploymentState.allocatableNodes()` now = `activeNodes() ∩ readyNodes` (intersection, NOT READY-alone — keeps membership invariant, degrades gracefully on handoff-warmup). Injected module-neutral `Supplier<Set<NodeId>> readyNodesSupplier` into `ClusterDeploymentContext` (threaded through `ClusterDeploymentManager.clusterDeploymentManager(...)`, now param #13, last). Wired in AetherNode via the existing late-binding ref pattern (`cdmReadyNodesRef`, mirrors `cdmSnapshotSupplierRef`) → `computeReadyNodes(fan, selfHolder, self)` = READY peers from `fan.readinessSnapshot()` **plus self** when `NodeReportedStateHolder.current()==READY` (the leader doesn't pong itself; local holder is the authoritative self source). Dead `isNodeOnDuty` KV read deleted. 509 aether-deployment tests green.

**Both layers now coexist correctly:** new readiness gate (fan view) for CDM allocation; old FSM→ON_DUTY→snapshot still feeds *membership* (`activeNodes`). Old `readyCandidate→ForceOnDuty` still runs but the CDM no longer reads its ON_DUTY for the gate.

## 1b. CRITICAL sequencing coupling discovered (reshapes B6/C order)
`activeNodes()` (the membership base that `allocatableNodes` intersects) derives from `ctx.snapshotSupplier()` → `ClusterGenerationProjector` → **`NodeLifecycleValue` (FSM-written)**. So **disconnecting the FSM (B6) BEFORE rebuilding the membership source empties `activeNodes()` → CDM can't place anything.** The true remaining order is therefore:
1. **C-membership-rebuild (do FIRST):** make `activeNodes()` / the generation snapshot / `MembershipView` / `ClusterPhaseView` derive from SWIM/NTT (`ntt.currentMembers()`) instead of `NodeLifecycleValue`. Substantial, careful — the heart of Phase C; needs its own design pass.
2. **B6:** disconnect FSM/reconciler/`readyCandidate→ForceOnDuty`/FSM-writer from AetherNode (now safe — membership is SWIM-derived). Truncation magnet — direct Read+Edit.
3. **C-deletion:** delete the now-dead types (FSM, reducer, LifecycleCommand, LifecycleReconciler+rules, LifecycleWriter, NodeLifecycleKey/Value + serializer, ProvisioningSlot* + CTM vestigial slot components, NodeReadinessTracker, JoinDeadlineExpired, DrainCoordinator). Bulk-delete dead tests.
4. **B5 (graceful drain):** ping `DRAIN` command + `CTM.drainNode`→command-enqueue. **Design subtlety to resolve first:** the graceful path commands the node to drain+`halt(2)`, but the *container* must then be reaped (provider-terminate) or Docker restart-policy revives it → overprovision loop. So `CTM.drainNode` = command drain → await DRAINING/grace → `lifecycleManager.terminateNode` (≈ the deleted FSM `terminateNodeWithDrainTimeout`, minus the FSM). Needs `DrainCommandRegistry` (leader-local set; written by CTM, read by `ClusterSyncContext.sendOnePing`; cleared on departure) + a `NodePingCommand{NONE,DRAIN}` field on `ClusterSyncPing` + node-side receiver → `DrainProcedure.initiate` + `holder.onDrainStarted`. Phase-A direct-terminate is the accepted interim until then.
5. **D:** REST/CLI/docs triad + chaos test rewrites + full suite run (first end-to-end v2 validation).

## 2. Two pivotal decisions made this session

### 2a. Orchestration-first cutover (replaces the prior type-first stage order)
The prior handover (2026-05-28b) ordered deletion as: LifecycleCommand → slots → NodeLifecycleKey → orchestration. **Investigation refuted its premise:** the FSM is still *live-wired* in AetherNode (`membershipFsm.start()`, SWIM/transport/self-bootstrap listeners, leader-toggled `LifecycleReconciler`), and CTM v2 methods still delegated into the *old* slot machinery. The old layer is one coupled **live** unit. Deleting the type first would stub live consumers only to delete them two stages later, and produce semantically-broken intermediates. **User chose orchestration-first:** make the new layer self-sufficient, switch consumers onto it, *then* delete the dead old layer. Each commit keeps the cluster functional.

### 2b. Leader↔node control-heartbeat for readiness + drain (NEW — spec §7.5)
Worked the CDM-readiness question from scratch with the user. Settled model (the node is the authority; the channel is the existing metrics ping/pong):
- **Readiness is derived, node-reported.** Node self-reports `SYNCING/READY/DRAINING` on the pong (it alone knows local `ConsensusActive` + subsystems-ready). Leader keeps an **in-memory** `(NodeId,incarnation)→state` view — never KV, self-cleaning via QUIC-disconnect/missed-pong eviction, rebuilt from pongs on handoff.
- **Drain is a ping command** (`DRAIN`), best-effort; **`DrainRequestKey` is removed from the design entirely** (a KV drain record has the same stale-GC problem as the deleted `NodeLifecycleValue`). In-progress drains survive leader change via the node's continued `DRAINING` self-report; operator-retry + CTM-re-derive cover the initiation-window leader change.
- **φ-accrual stays deleted** — missed-pong subsumes black-hole detection (integer count vs suspicion level).
- **Stuck-SYNCING reaper** — leader countdown per `(NodeId,incarnation)` → terminate stuck node → auto-heal.
- **QUIC connect/disconnect** → first-class `Message.Local` routed events.
- **No new KV records at all**; `NodeLifecycleKey` becomes fully deletable (no writer, no reader).
Two accepted tradeoffs (recorded as spec I13/I14): readiness not in KV (reconstructible from pongs); drain command best-effort. Full design: **spec §7.5 + I13/I14** at `aether/docs/specs/membership-architecture-v2-spec.md` (changelog row 2026-05-29).

## 3. What's DONE (committed + verified)

### Phase A (584743ebf, a02fe7842) — CTM is now a state-derived actuator
- `ClusterTopologyManagerRecord`: `provisionReplacement`→`lifecycleManager.provisionNode` (direct, PEERS-seeded), `drainNode`→`lifecycleManager.terminateNode` (interim direct-terminate; superseded by B5 command), internal slot loop OFF (`activate()` no longer seeds/polls), ~1100 lines of slot WRITE-path deleted. `reconcile()` no-op.
- **`LeaderReconciler` is the sole provisioning driver** (already does spec §7 state-derived shortage/surplus). **Restored the quorum-safety guard** the rewrite had dropped: `runReconcileBody` now gates provision AND drain on `clusterMembershipCount ≥ quorumThreshold(configured)` (SWIM-based; TODO 2c-α.3 to tighten to QUIC-confirmed `LocalQuorumWatcher` once its config-count is wired — currently dormant).
- 14 dead CTM slot-test files deleted → one `ClusterTopologyManagerActuatorTest`; `LeaderReconcilerTest` gained `runReconcile_belowQuorum_suppressesProvisioning`. aether-deployment: 509 tests green.
- Also fixed the pre-existing `integrations/cluster` test-compile break (3 files still importing `QuorumStateNotification` — completes last session's 2c.0 rename).

### B1 (dc3c235c3) — node-reported readiness on the pong
- New `NodeReportedState{SYNCING,READY,DRAINING}` + `NodeReportedStateHolder` (sticky DRAINING per I9; READY needs consensus-active AND subsystems-ready) + `BootEpoch` (incarnation = `System.nanoTime()` at process start — SWIM incarnation not reachable from aether-metrics). 7 holder tests green.
- `ClusterSyncPong` gained `long incarnation`; `buildPong` sources `lifecycleState` from the holder + `incarnation` from BootEpoch (back-compat ctors preserved).
- **AetherNode wiring (done directly):** holder constructed in `assembleNode`; collector setters; `ClusterStateNotification` route → `routeConsensusEdgeToReportedState` (ACTIVE→onConsensusActive else onConsensusPassive); `setSelfReadySignal` wrapped via `markSubsystemsReady`; quorum-loss chain `.andThen(onDrainStarted)`. (readyCandidate/ForceOnDuty path still present — deleted in B6.)

### B2 (868fdd97f) — leader-side readiness view
- `ClusterSyncPongSignalFan` converted functional→stateful: epoch-fenced `(NodeId,incarnation)→ReadinessEntry` map populated from pongs (higher incarnation replaces, equal updates, lower ignored), stuck-SYNCING countdown reaper (`SYNC_REAP_THRESHOLD=30`, warmup-gated, fires injected `onStuckSyncing`), `evict(NodeId)` / `sweepStale(maxAgeNanos)` / `readinessSnapshot()` API, injectable `nowNanos`/`warmedUp`/`onStuckSyncing` (safe defaults; `warmedUp` defaults FALSE — reaper disabled until wired). `NOOP` constant. 14 new tests green (225/225 module-wide).

## 4. Remaining plan (tasks #6,#7,#8,#9,#3,#4)

- **B3 — QUIC connect/disconnect as `Message.Local` routed events (epoch-matched).** Producer = `QuicClusterNetwork` PeerState ADD/REMOVE (`:1294/1302`) → `PeerConnectivityReporter`. Re-express the current consumers (NTT `onQuicReconnect`, `LocalQuorumWatcher` on/off-peer, `ReachabilityAggregator.ingestSelfTransition`, `PeerObservationBuffer`) as router subscriptions; ADD `fan.evict(X)` on disconnect. **Trap:** the transport epoch the connectivity layer carries is the *leader-term* (`leaderTerm` at AetherNode `:375`, counter hardcoded 0), NOT the node incarnation the fan keys on — decide eviction epoch-policy (simplest: evict-then-reinstall-on-next-pong, ~1 ping flicker; or track last-connect transport-epoch per node). Touches integrations/consensus + AetherNode `attachQuicConnectivityReporter` (`:2399-2453`).
- **B4 — rewire CDM allocatable-gate to the readiness view (the functional readiness switch).** `ClusterDeploymentState.isNodeOnDuty` (`:743`, inside `allocatableNodes` `:704`) currently reads KV `ON_DUTY`. Inject a `Supplier<Map<NodeId,NodeReportedState>>` into `ClusterDeploymentContext` (`:34-50`; it has NO leader-side view today — `router`/`kvStore`/`snapshotSupplier` only) fed by `fan.readinessSnapshot()`; gate allocatable = `READY`. Wire the supplier in AetherNode.
- **B5 — ping `DRAIN` command + `CTM.drainNode`→command-enqueue.** Add per-target command to `ClusterSyncPing` (built `ClusterSyncContext.sendOnePing` `:311`, leader-gated dispatch `ClusterSyncState:136`); leader includes `DRAIN` for nodes in its drain set; node receiver (`ClusterSyncCollector.onClusterSyncPing` `:277`) → `DrainProcedure.initiate` + holder `onDrainStarted`. `CTM.drainNode` from Phase-A direct-terminate → enqueue target in the leader drain set. Supersedes the Phase-A interim AND E6's DrainRequestKey.
- **B6 — disconnect old layer from AetherNode (TRUNCATION MAGNET — do via direct Read+Edit, not delegated).** Remove `MembershipFsm` construction/`start()`/SWIM+transport+self-bootstrap listeners, `LifecycleReconciler` + leader toggle, `bootstrapSelfOnDutyOnActive`, the `readyCandidate→ForceOnDuty` chain + `NodeReadinessTracker` wiring, the FSM-routed `LifecycleWriter`. New path becomes sole driver. (`isNodeOnDuty` already off-KV after B4.)
- **C — delete now-dead types + rebuild derivation primitives.** `LifecycleCommand` hierarchy, `MembershipFsm`+reducer+states, `LifecycleReconciler`+7 rules, `LifecycleWriter`+impls, `JoinDeadlineExpired`, `DrainCoordinator`/`NoOpDrainCoordinator`, `DecommissionedAtomGc`; `ProvisioningSlotKey/Value` + serializer cases + CTM record's now-vestigial slot components (`slotReader`/`lifecycleWriter`/`drainCoordinator`); `NodeLifecycleKey/Value` + serializer cases + `NodeReadinessTracker`/`readyCandidate`. Rebuild `MembershipView`/`ClusterPhaseView` over NTT (D1/D2/D3); add `NTT.quorateSinceNanos`. Bulk-delete dead tests. Rewire `ClusterDeploymentState.allocatableNodes` consumers if needed.
- **D — REST/CLI/docs triad + chaos tests.** Drop `NodeLifecycleRoutes`/`/api/cluster/slots`/reconciler-status; rebuild `/api/cluster/membership` over NTT; CLI subcommands; `management-api.md`+`cli.md`+ManagementRoute enum; chaos test rewrites (S01 join-window-kill, S19/S20 self-drain) + full suite run = first end-to-end v2 validation.

## 5. New public APIs (for wiring in B3/B4/B5)
- `NodeReportedStateHolder.nodeReportedStateHolder()`; `onConsensusActive/Passive()`, `onSubsystemsReady()`, `onDrainStarted()`, `current()`.
- `ClusterSyncPongSignalFan`: `readinessSnapshot(): Map<NodeId,NodeReportedState>`, `evict(NodeId)`, `sweepStale(long)`, setters `onStuckSyncing(Consumer<NodeId>)`, `warmedUp(BooleanSupplier)`; 4-arg factory adds `LongSupplier nowNanos`; `NOOP` constant. **`warmedUp` defaults FALSE — must be wired (to a leader-warmup signal, e.g. leaderActivationDelay-after-leader-gain) before the reaper does anything.**

## 6. Traps / notes
- **AetherNode is one ~3300-line `assembleNode` method (472→past 2100).** Holder etc. are in scope throughout. AetherNode edits: do directly (B6). `aetherEntries` (1490, = `collectRouteEntries` result + local adds) and the `ClusterStateNotification` routes at ~2990 share the same router.
- **15 pre-existing lint errors in aether/node** (JBCT-RET-01 voids in `send`/`route`/`onPeerConnected` etc. + AlertManager/ProblemResponses/SwimHealthState/ContainerLabelInspector). NOT ours; `install` is green; known debt that can block `./build.sh` (use focused `install -Dmaven.test.skip=true` per memory).
- **Verify builds with `-Dmaven.test.skip=true`, NOT `-DskipTests`** (the latter hits an unrelated test-compile path). Never `mvn verify` (HCLOUD failsafe). build-runner owns maven.
- A `jbct-coder` delegation dropped once on a transient socket error (left nothing on disk; clean retry succeeded). Inspect tree before assuming partial work.

## 7. References
- Spec: `aether/docs/specs/membership-architecture-v2-spec.md` (§7.5 + I13/I14 are this session's).
- Prior: `session-handover-2026-05-28b.md` (the type-first plan this session inverted).
- Memory: `[[project_membership_v2_redesign]]`.
