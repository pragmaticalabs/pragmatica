# Cluster State Convergence — Lifecycle FSM Redesign & Reconciler

**Status:** DRAFT — open decisions closed, ready for implementation review.
**Scope:** RC1 (foundational; closes a class of cluster-state bugs and operator-recovery gaps; pre-GA, no backward compatibility burden).
**Related specs:**
- [`cluster-membership-fsm-spec.md`](cluster-membership-fsm-spec.md) — existing reducer + event model (to be updated)
- [`cluster-generation-spec.md`](cluster-generation-spec.md) — Rabia-derived generation tracking
- [`cluster-management-spec.md`](cluster-management-spec.md) — operator-facing surface
- [`state-authority.md`](../specs/state-authority.md) — two-endpoint contract for FSM vs MembershipView

---

## 1. Problem

Aether maintains four parallel "membership" state machines:

| Source | Owner | What it tracks | How it updates |
|---|---|---|---|
| **Rabia consensus generation** | Consensus protocol | who's replicated to / voting | consensus transaction |
| **SWIM gossip** | SWIM module | who's network-reachable (Alive/Faulty/Departed) | gossip rounds, probe timeouts |
| **NodeLifecycleKey FSM** | leader's reducer | operational state (JOINING / ON_DUTY / DRAINING / STOPPED) | reducer reacts to inputs |
| **MembershipView** | each node (local) | "the view I use for routing" | derived from SWIM + leader's NodeLifecycleKey writes |

These are **loosely coupled via event propagation**. There is no global convergence verifier. When propagation fails (event lost, leader handover during window, cold-boot suppression, SWIM probe gap), divergence persists silently — often forever within a test run's budget.

### Observed symptoms (the trigger for this spec)

**Symptom A:** `pick_non_leader` in integration tests reports:

> `lifecycle reports 'node-2' as ON_DUTY but no live container carries label aether.node-id=node-2 — skipping stale candidate`

**Symptom B:** The TODO in `restore_cluster_baseline` documents:

> *"Post-chaos, the CTM replacement IS alive in generation within seconds (`Auto-heal_restores_to_5` confirms) but the entry-point's MembershipView stays at 4 for the full 1200s budget. Static analysis couldn't discriminate; runtime logs needed."*

**Symptom C:** A JOINING node that never progresses can sit in the FSM indefinitely (joining-window-kill test 6, fixed in `c8d6f6faa` for the container-gone case; the container-alive-but-stuck case remains unobservable).

All three are instances of the same structural gap.

### The structural gap

The current FSM is **event-only** — it consumes observed events (`SwimHealthy`, `SwimFaulty`, `SwimDeparted`) and reacts. There is **no intent channel** for any party (operator, reconciler, leader itself) to inject "this state SHOULD be X" when the event flow has failed silently. Recovery requires the event flow to self-heal, which it sometimes doesn't.

The structural fix has three parts, walked in §3–§5:

1. **Topology model:** identify which layer of state ownership we're modifying, and where the gaps actually live.
2. **FSM redesign:** add a node-local SYNCING sub-phase with a candidate-field protocol, collapse terminal states.
3. **Reconciler + Command primitive:** an intent channel that observes the three sources of truth and emits commands to reconcile divergence.

## 2. Goals

**G1 — Closed-loop convergence.** Differences between observable sources of truth detected and resolved within a bounded budget (default: 60s after entering NORMAL phase).

**G2 — Recoverable from stuck states.** Operators have an intent channel to drive specific transitions when the event path has failed. Single-writer safety preserved (only leader's reducer writes NodeLifecycleKey).

**G3 — Observable silence.** Every expected transition has a budget; budget overrun emits an audit log entry consumable by external management systems (humans + LLM-based ops agents). Silent stalls become observable.

## 3. Non-goals

- Replacing SWIM, Rabia, or the existing reducer. This is additive on top of the existing FSM.
- Bypassing consensus. All reducer-emitted writes flow through Rabia.
- Test-only API for state override. The intent channel is operator-facing; tests use it the same way operators do.
- A generic `Reconciler<I,C>` framework. Lifecycle convergence is concrete and bounded; abstracting against one user would bake its specifics into the abstraction. CTM stays as-is.

## 4. Topology model

Three layers, three ownership classes. Pulling them apart makes the gap clearer than the original four-machines-loosely-coupled framing.

### 4.1 Three-layer stack

```
  Workload    │ Slot / Deployment / Schema migration
  ────────────┼──────────────────────────────────────
  Topology    │ Lifecycle FSM (this spec)
  ────────────┼──────────────────────────────────────
  Authority   │ Rabia consensus + Leader lease
```

Workload state depends on Topology. Topology depends on Authority. This spec is entirely within the Topology layer.

### 4.2 Three ownership classes within Topology

| Class | Examples | Single-writer rule? |
|---|---|---|
| **Single-writer authoritative** | `NodeLifecycleKey` (one entry per node) | Yes — leader's reducer is the sole writer |
| **Distributed self-owned** | SWIM per-peer Alive/Suspect/Faulty observations | Each peer authors its own observation; no single writer |
| **Derived views** | `ClusterPhase`, `MembershipView`, generation membership snapshot | Read-only projections from the two above |

The convergence problem lives in propagation between these classes: a derived view that doesn't catch up with the authoritative atom, or an authoritative atom that doesn't reflect what the distributed observations are telling it.

## 5. Lifecycle FSM redesign

### 5.1 States

**KV (cluster-authoritative):**

```
JOINING → ON_DUTY → DRAINING → STOPPED
```

`NodeLifecycleState` enum collapses to four values. Cause-of-stop carried in a sidecar field:

```java
enum NodeLifecycleState { JOINING, ON_DUTY, DRAINING, STOPPED }

enum StopReason { GRACEFUL, FORCED, DRAIN_FAILED }

record NodeLifecycleValue(
    NodeLifecycleState state,
    Option<StopReason> stopReason,   // populated iff state == STOPPED
    long enteredAtMs,
    long observedCoreEpoch,
    Cause lastTransitionCause
) {}
```

The previous three-terminal proliferation (`DECOMMISSIONED`, `SHUTTING_DOWN`, `FAILED_DRAIN`) collapses to one terminal with the cause moved to the sidecar. Pre-GA — no migration burden.

**Node-local view:**

```
JOINING ⇄ SYNCING → ON_DUTY → DRAINING → STOPPED
```

`SYNCING` is a node-local sub-phase of cluster-side `JOINING`. The KV value stays `JOINING` from registration until consensus commits `ON_DUTY`; the SYNCING transition is invisible to the cluster. The `⇄` between JOINING and SYNCING is driven by quorum availability and exists in node memory only.

### 5.2 SYNCING — what it means

A node is `SYNCING` (node-locally) once its Rabia layer begins consuming the cluster snapshot. Sync completes when Rabia has fully restored the KV and fired all KV-derived events.

Sync requires quorum. If quorum disappears mid-sync, the node falls back to `JOINING` (node-locally); Rabia transfer pauses. When quorum returns, the node re-enters `SYNCING` from wherever it stopped. **No KV state changes during this bounce** — the cluster-side `NodeLifecycleKey` stays `JOINING` throughout. The bounce is purely an in-memory phenomenon.

**No SYNCING timeout.** A clean node mid-sync is not a candidate for force-decommission. If the container is alive and the node is making (or trying to make) Rabia progress, it stays until either:
- Sync completes → candidate field flips on pong → consensus promotes to ON_DUTY, or
- Container dies → SWIM marks Faulty/Departed → the `JoiningTimeout` reconciler rule fires (§7).

### 5.3 The candidate-field protocol

The transition SYNCING → ON_DUTY uses an existing cluster-sync channel — empirically verified in code (`ClusterSyncMessage.ClusterSyncPong` is a record with tag-based codec; trailing-field extension is backward-compatible).

**Protocol:**

1. Leader's `Pinging` FSM dispatches sync pings at 1s cadence. JOINING peers are already in the dispatch topology (`ClusterSyncScheduler` augments topology on `NodeJoining`).
2. Node receives pings throughout `JOINING` and `SYNCING`. Pongs are routine until sync completes.
3. Once node-local sync completes, node populates a new trailing field on the pong:

```java
record ClusterSyncPong(
    NodeId sender,
    Map<String, Double> metrics,
    long observedRabiaTerm,
    long observedEpochTerm,
    long observedEpochCounter,
    String lifecycleState,
    List<CommunityReport> communityReports,
    List<PeerHealthObservation> peerHealth,
    List<PeerConnectivityObservation> peerConnectivity,
    Option<NodeId> readyCandidate    // NEW — non-empty means "promote me to ON_DUTY"
) {}
```

4. Leader receives pong with `readyCandidate` non-empty → emits `ForceOnDuty(nodeId)` command into the reducer (this is reducer-internal, not an operator-facing command).
5. Consensus commits `NodeLifecycleKey(nodeId).state = ON_DUTY`.
6. Node observes its own `ON_DUTY` in the KV event stream → clears `readyCandidate` from subsequent pongs and updates its local view to `ON_DUTY`.

**Idempotence:** if the leader receives `readyCandidate` again after consensus has committed (in-flight pong overlap), the reducer treats `ForceOnDuty` on already-ON_DUTY as a no-op.

**Chicken-and-egg resolution:** consumers of KV events that gate on "node is ready" must accept either SYNCING or ON_DUTY in their gate. The node sets its local view to `ON_DUTY` only after observing the committed atom. This narrows but doesn't eliminate the pre-official window — confined to one node's local subsystems, not visible cluster-wide.

### 5.4 Leader-side sync hold

To prevent force-decommissioning a healthy-but-busy SYNCING node (e.g., one whose SWIM heartbeats lag while consuming a fat snapshot), the leader maintains an in-memory hold:

```java
final Map<NodeId, Long> activeSyncHolds = new ConcurrentHashMap<>();

// On Rabia sync-request service (leader-side, before sending snapshot):
long scaledDeadline = nowMs() + bytesToHoldMs(snapshotBytes);
activeSyncHolds.put(targetNodeId, scaledDeadline);

// bytesToHoldMs = clamp(snapshotBytes / EXPECTED_SYNC_BPS, MIN_HOLD_MS, MAX_HOLD_MS)
// Defaults: MIN=5s, EXPECTED_BPS=10MB/s (conservative), MAX=60s
```

The reconciler's force-decommission rules consult `activeSyncHolds` and skip nodes whose deadline hasn't expired. Cleared on `readyCandidate` arrival or deadline expiry.

**Leader handover:** `activeSyncHolds` is in-memory. On handover, the new leader either (a) reconstructs from Rabia's currently-in-flight transfers, or (b) accepts a brief unprotected window — handovers themselves stall the reconciler for the stability-window seconds, so (b) is acceptable for RC1.

**Stuck sync:** if `readyCandidate` never arrives and the hold expires, the node falls back into normal reconciler logic. If the container is also gone (SWIM Faulty), `JoiningTimeout` fires. If the container is still alive, the node is alert-only — clean nodes are never auto-decommissioned (§7).

## 6. Command primitive

Extend the FSM input alphabet from `Event` only to `Event | Command`:

```java
sealed interface LifecycleInput permits LifecycleEvent, LifecycleCommand {}

sealed interface LifecycleCommand extends LifecycleInput {
    NodeId nodeId();
    Cause justification();

    record ForceDecommission(NodeId nodeId, Cause justification) implements LifecycleCommand {}
    record ForceOnDuty(NodeId nodeId, Cause justification) implements LifecycleCommand {}
    record RecordJoining(NodeId nodeId, Cause justification) implements LifecycleCommand {}
    record RequestReJoin(NodeId nodeId, Cause justification) implements LifecycleCommand {}
}
```

The reducer's signature evolves from `(state, event) → outcome` to `(state, input) → outcome`. Commands are valid for some transitions but not others — the reducer rejects illegal commands with a `Cause` (e.g. `ForceDecommission` on `DRAINING` is a no-op, because that's the natural drain path).

**Why commands, not direct writes:** preserves single-writer rule. The leader's reducer remains the sole authoritative function deciding "does this transition fire?" Any party (operator, reconciler, internal handler) only emits commands.

**Why a `Cause`:** every forced transition has a written justification. Audit log captures it. Operators (and LLM-based ops agents) reading post-incident understand why.

### 6.1 Command transport

Commands are submitted via two paths:

1. **Operator API** — `POST /api/nodes/lifecycle/commands` with body `{"type": "FORCE_DECOMMISSION", "nodeId": "node-2", "cause": "operator manual recovery from stuck FSM"}`. Forwards to leader (existing `LEADER` route target pattern).

2. **Internal reducer/reconciler** — `lifecycleWriter().applyCommand(command)`. No HTTP round-trip. Used by:
   - The `LifecycleReconciler` (§7) emitting reconciliation commands.
   - The leader's pong handler emitting `ForceOnDuty` on `readyCandidate` arrival.

Commands are **NOT persisted in KV.** They are in-flight signals; their effect (the resulting state transition) IS persisted. This matches event handling and avoids replay ambiguity on leader handover.

**Operator API contract is synchronous-on-consensus:** `POST /api/nodes/lifecycle/commands` returns 2xx only after the resulting consensus writes are accepted. 5xx / timeout means "may or may not have applied — query `GET /api/nodes/lifecycle/{id}` to verify, then retry if needed." Same contract as existing `POST /api/nodes/drain`.

**Failure window:** if the leader dies after receiving a command but before consensus accepts the writes, the command is dropped. Recovery: reconciler-emitted commands are idempotent (re-derived from observable state, re-emitted next tick); operator-emitted commands rely on standard HTTP retry semantics.

### 6.2 Migration of existing call sites

Today's code has direct `lifecycleWriter().requestFailedDrain(...)`, `requestForceDecommission(...)` etc. With the LifecycleCommand interface, call sites split into two classes:

| Class | Examples | Disposition |
|---|---|---|
| **Reducer-internal reactions** | `MembershipReducer.joiningToOnDuty` triggered by `SwimHealthy` | Stay inside reducer (they're event-driven, not commands) |
| **External-trigger writes** | Operator API handlers, reconciler emissions, drain workflow callbacks, terminal-state writes | Migrate to `LifecycleCommand` via `applyCommand` |

All kind-2 sites migrate as part of this work — not deferred. Single ingress for state changes is the structural point of the redesign.

## 7. Reconciler

Runs on the leader only (tied to leader lease). Periodic tick during `NORMAL` phase. **No-op during `COLD_BOOT` and all `RECOVERING` sub-branches** (SubQuorum, NoLeader, StabilityWindow). The phase gate from Path 2 v2 generalizes here.

**Tick rate:** 10s default, configurable via `aether.toml [reconciler]` with bounds `[5s, 60s]`. Sub-5s overlaps the SWIM probe cycle and produces noise; >60s makes operator-perceived recovery sluggish.

Each tick:

1. Snapshot `NodeLifecycleKey` (all entries, all states).
2. Snapshot SWIM view (`PeerObservationStore`).
3. Snapshot consensus generation members.
4. Snapshot `activeSyncHolds` (skip set).
5. For each entry: compute the expected state from observations, compare to FSM state, emit a command if they disagree AND the disagreement has persisted past its budget AND any rule-specific preconditions hold AND the node isn't in the active-sync skip set.

**Budget calibration:** budgets are expressed as multiples of existing protocol constants so the reconciler stays a backstop. If `JOIN_DEADLINE` is bumped from 60s → 120s, the reconciler's JOINING budget tracks automatically.

### 7.1 Rules

| Rule | Observation | Budget | Precondition | Command emitted |
|---|---|---|---|---|
| `JoiningTimeout` | JOINING entry | `JOIN_DEADLINE × 1.5` | **SWIM `Faulty` or `Departed`** (container demonstrably gone) AND not in `activeSyncHolds` | `ForceDecommission(reason="joining + container gone, JOIN_DEADLINE × 1.5 elapsed")` |
| `JoiningStuckAlert` | JOINING entry | `JOIN_DEADLINE × 3` | SWIM `Alive` (container alive but not progressing) | Audit-log entry only — alert-only, no force action |
| `OnDutyFaulty` | ON_DUTY entry | `SWIM_FAULTY_DECLARATION × 3` | SWIM emitted positive `Faulty` (not mere absence from `Alive`) | `ForceDecommission(reason="swim faulty for ≥ 3× declaration window")` |
| `DrainTimeout` | DRAINING entry | `DRAIN_DEADLINE × 1.5` | none | `ForceDecommission` with terminal `StopReason=DRAIN_FAILED` |
| `GenerationLifecycleGap` | Generation member, no NodeLifecycleKey entry | 30s | none (race window is normally sub-second) | `RecordJoining(reason="generation member without lifecycle")` |
| `SwimLifecycleGap` | SWIM-Alive node, no NodeLifecycleKey entry | 30s | no historical NodeLifecycleKey entry for this nodeId in audit log within last 1h | `RecordJoining(reason="swim alive without lifecycle")` |
| `StoppedZombie` | STOPPED entry, container/process running | 30s | none | Audit-log entry only — invariant violation, surfaces to alert |

The cleanup-driven shift compared to the original draft: `JoiningTimeout` narrows to "container demonstrably gone" (the `JoiningStuckAlert` rule absorbs the clean-but-stuck case). `DrainTimeout` is new (replaces the old `FAILED_DRAIN` direct-write path with a command-routed equivalent). `DecommissionedZombie` renamed `StoppedZombie` (terminal consolidation). All terminal-or-permanent rules either fire `ForceDecommission` or emit alert-only audit entries — no other side effects.

**Per-rule enable flags** in `[reconciler.rules]` of `aether.toml`. Default in dry-run mode (Phase 3): all rules ON, all log-only. Default in enforcing mode (Phase 4): all rules ON, all enforcing.

Rules are **idempotent** — same input produces same command set. The reducer is the natural deduplicator (applying `ForceDecommission` twice to an already-STOPPED node is a no-op).

### 7.2 Watchdog (folded into state-entry deadlines)

No dedicated watchdog component. Each state-entry that has a deadline (`JOIN_DEADLINE`, `DRAIN_DEADLINE`) writes its deadline as a KV atom alongside the lifecycle write. The reconciler reads these deadlines the same way it reads any other input — deadline expiry is just another rule precondition.

This avoids a parallel watchdog process and unifies the observation surface.

### 7.3 Audit log

Every command **received by the leader** → audit log entry, regardless of whether it ends up applied. Two events per command lifecycle:

1. **`CommandReceived`** — `(timestamp, command, justification, source, decision: APPLIED | REJECTED_ILLEGAL_TRANSITION | LOST_LEADER_DIED)`. The `LOST_LEADER_DIED` cases are recorded by the *next* leader during reconciler observation — the original leader can't record its own death.
2. **`CommandApplied`** — `(timestamp, command, resulting_state, writes)`. Emitted only on the APPLIED path, immediately after consensus accepts the writes.

`source` is `OPERATOR | RECONCILER | INTERNAL`. Uses the existing `AuditLog.nodeLifecycleTransition` channel as the underlying mechanism; commands add a new variant.

**Audit log is the observability surface for autonomous behavior.** It's the input for external management systems (human operators + LLM-based ops agents). Integration tests query it directly — no parallel ring buffer needed.

## 8. API surface

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/nodes/lifecycle/commands` | Submit a lifecycle command (forwards to leader) |
| `GET` | `/api/audit/commands?source=...&since=...` | Query audit log; consumable by operators, LLM agents, and tests |
| `GET` | `/api/cluster/health/transitions` | List pending transitions and deadline status |
| `GET` | `/api/nodes/lifecycle/reconciler` | Reconciler status (last tick, last action, rules enabled) |

CLI updates:
- `aether nodes decommission <node-id> --reason "..."` — wraps `ForceDecommission` command
- `aether cluster audit --source reconciler --since 1h` — wraps audit query
- `aether cluster health transitions` — wraps `/api/cluster/health/transitions`
- `aether cluster reconciler status` — wraps the reconciler GET

## 9. Implementation phases

| Phase | Deliverable | Risk |
|---|---|---|
| **1** | `NodeLifecycleState` collapsed to 4 values + `StopReason` sidecar. `LifecycleCommand` types + reducer extension. `applyCommand` on `lifecycleWriter`. Migration of kind-2 call sites. Unit tests for each command on each state. | Medium — touches many files (kind-2 migration). |
| **2** | SYNCING node-local sub-phase. `readyCandidate` field on `ClusterSyncPong` + codec extension. Leader-side `activeSyncHolds`. Integration test: node restart → sync → ON_DUTY via candidate field. | Medium — protocol change, but well-isolated to cluster-sync layer. |
| **3** | API endpoint + CLI for `ForceDecommission`. Integration test: operator can manually decommission a stuck ON_DUTY entry. | Low — single command path. |
| **4** ✅ | `LifecycleReconciler` with rules enabled CONSERVATIVELY (large budgets, dry-run mode emitting audit entries only). Run on cluster B integration tests for a week. | Medium — false positives could cause cascading decommissions. Dry-run gate first. |
| **5** | Switch reconciler to enforcing mode. Audit-log query API surfaces transitions. | Low after Phase 4 verifies dry-run quiet. |

Phase 1+3 unblocks the immediate 02-chaos issue: tests can manually clean up stuck states using a documented operator API. Phase 4+5 makes the cluster self-healing.

### 9.1 Phase 4 PR-D — concrete implementation defaults

Landed `[reconciler]` section in `aether-config`. Default values when no operator config
is present:

| Key | Default | Notes |
|---|---|---|
| `enabled` | `true` | Global on/off; `false` keeps the periodic tick unscheduled even when the node is leader. |
| `tickInterval` | `10s` | Spec §7 bound `[5s, 60s]`. |
| `recentDecisionsCapacity` | `50` | Per-rule ring buffer feeding the status endpoint. |
| `rules.joiningTimeout` | `{enabled=true, enforce=false}` | Phase 5 flips to `enforce=true`. |
| `rules.joiningStuckAlert` | `{enabled=true, enforce=false}` | Spec §7.1 — stays audit-only forever. |
| `rules.onDutyFaulty` | `{enabled=true, enforce=false}` | Phase 5 flips. |
| `rules.drainTimeout` | `{enabled=true, enforce=false}` | Phase 5 flips. |
| `rules.generationLifecycleGap` | `{enabled=true, enforce=false}` | Phase 5 flips. |
| `rules.swimLifecycleGap` | `{enabled=true, enforce=false}` | Phase 5 may flip; lookback guard required first. |
| `rules.stoppedZombie` | `{enabled=true, enforce=false}` | Spec §7.1 — stays audit-only forever. |

Component wiring (per `aether/node/.../AetherNode.java`):
- Leader gating: activated on `LeaderNotification.LeaderChange` via
  `toggleReconcilerOnLeaderChange` alongside the symmetric CTM toggle.
- Phase gate: pulls `effectivePhaseSupplier` (the same `ClusterPhaseView.compute()` the
  CTM consults); ticks no-op when phase ≠ `NORMAL`.
- SWIM input: `CoreSwimHealthDetector.currentHealth()` projected to `Map<NodeId, SwimHealth>`
  per tick. The reconciler maintains its own per-peer "since-this-health" timestamps by
  diffing consecutive snapshots — no external "since" feed required.
- Sync-hold input: `SyncHoldRegistry.activeHolds()` (Phase 2 PR-B).
- Generation input: `GenerationSnapshotSource.currentMembershipView()` from the leader-aware snapshot source.
- Audit emission: when a rule fires with `enforce=false`, the reconciler publishes a
  `CommandReceived(accepted=false, source=RECONCILER)` directly on the
  `audit.lifecycle.commands` stream (also tee'd into `RecentCommandsBuffer`). When
  `enforce=true`, the reconciler routes through `LifecycleWriter.applyCommand(...,
  SOURCE_RECONCILER)` (which itself emits the `CommandReceived` + `CommandApplied`
  pair). The two paths share the audit-stream schema.

Observability:
- `GET /api/nodes/lifecycle/reconciler` — new endpoint, returns
  `{active, phase, lastTickAt, lastActionAt, rules[], recentDecisions[]}`. Documented
  in `aether/docs/reference/management-api.md`.
- `aether cluster audit --source reconciler` — existing CLI; surfaces the dry-run
  would-have-fired set via the audit stream tee.

### 9.2 Phase 5 PR-E — enforcing flip

Phase 5 PR-E flips `ReconcilerConfig.defaults()` from the Phase 4 dry-run shape to an
enforcing baseline. The flip lives entirely in `ReconcilerRulesConfig` — a new
`enforcingDefaults()` factory is introduced alongside the existing `dryRunDefaults()`,
and `ReconcilerConfig.defaults()` calls the enforcing factory.

| Rule | Phase 5 default | Rationale |
|---|---|---|
| `joiningTimeout` | `enforce=true` | Phase 4 dry-run confirmed quiet; flips the §7.1 line. |
| `joiningStuckAlert` | `enforce=false` (forever) | Observation-only — alerts on stuck-but-alive containers; operator decides whether to intervene. |
| `onDutyFaulty` | `enforce=true` | Phase 4 quiet; SWIM `Faulty for ≥ 3× declaration window` is a definitive signal. |
| `drainTimeout` | `enforce=true` | DRAIN_FAILED terminal — preferable to a zombie DRAINING entry. |
| `generationLifecycleGap` | `enforce=true` | Auto-heals the JOINING-lifecycle write race; idempotent. |
| `swimLifecycleGap` | `enforce=true` | Lookback guard already in place (snapshot's last-1h audit window — see §7.1 row). |
| `stoppedZombie` | `enforce=false` (forever) | Invariant-violation surface; emits an alert so the responsible component can be diagnosed. The orchestration layer kills the container separately. |

**Operator escape hatch.** Operators flip individual rules back to dry-run via TOML
override. The reconciler section in `aether.toml` accepts per-rule `enforce` toggles:

```toml
[reconciler.rules.joiningTimeout]
enforce = false

[reconciler.rules.onDutyFaulty]
enforce = false
```

Setting all five enforcing rules to `enforce = false` resolves to the Phase 4 dry-run
shape (equivalent to passing `ReconcilerRulesConfig.dryRunDefaults()` to
`ReconcilerConfig` directly in tests). Rules are never auto-disabled — `enabled=true`
holds in both factories; the escape hatch flips `enforce`, not `enabled`.

**Audit payload semantics.** The two dispatch paths share the
`audit.lifecycle.commands` stream:

- **Audit-only path (`enforce=false`)** — reconciler publishes a single
  `CommandReceived(source=RECONCILER)` event with no follow-on `CommandApplied`. No KV
  write happens. The "would have fired" set is reconstructed by filtering audit events
  for `source=RECONCILER` and `commandType ∈ {ForceDecommission, ForceDrain, ...}`
  without a matching `CommandApplied`.
- **Enforcing path (`enforce=true`)** — reconciler routes through
  `LifecycleWriter.applyCommand(command, SOURCE_RECONCILER)`. The writer emits
  `CommandReceived(source=RECONCILER)` immediately and then `CommandApplied(...,
  accepted=true)` on KV-write success (or `accepted=false` if the apply future fails).
  The per-rule decision is also recorded in the reconciler's ring buffer with
  `enforced=true`.

Both paths additionally tee the events into the per-node `RecentCommandsBuffer` for
the `GET /api/audit/commands?source=reconciler` query surface.

## 10. Failure modes & mitigations

| Failure | Mitigation |
|---|---|
| Reconciler emits a wrong command (false-positive force-decommission) | Dry-run mode for Phase 4. `Cause` field is mandatory. Operator can audit pre-enforcement. `JoiningStuckAlert` (alert-only) catches the bulk of false-positive risk for the JOINING side. |
| Reconciler stalls (its own thread dies) | Leader-lease lapse → new leader runs a fresh reconciler. |
| Leader handover mid-sync (active hold lost) | New leader rebuilds holds from Rabia's in-flight transfers OR accepts brief unprotected window (≤ stability-window seconds, since reconciler is no-op during RECOVERING). |
| Operator submits a malicious command | Same auth as existing management API. Audit log captures it. |
| Command storm (operator script gone wrong) | Same rate limiting as the rest of the management API. Single-writer reducer naturally serializes. |
| Leader handover mid-reconciliation tick | Commands not persisted; in-flight ones dropped. Next leader's reconciler observes same state, re-emits identical commands. Idempotent. |
| `readyCandidate` pong dropped (network) | Next pong (1s later) re-carries it. Cadence is the recovery. |
| SYNCING node's container dies mid-sync | SWIM Faulty/Departed → `activeSyncHolds` deadline expires → `JoiningTimeout` fires force-decommission. |

## 11. Closed decisions (record)

| ID | Decision | Outcome |
|---|---|---|
| D1 | Reconciler scope | **DROPPED** — no second user; no `Reconciler<I,C>` framework. Lifecycle reconciler is a concrete component. |
| D2 | Command persistence | **RESOLVED** — in-memory + audit-log every received command. |
| D3 | Tick rate and budgets | **RESOLVED** — 10s default, calibrated to protocol constants, per-rule enable flags. |
| D4 | Phase gate | **NORMAL only.** No-op during COLD_BOOT and all RECOVERING sub-branches. |
| D5 | Watchdog | **Shared** — folded into state-entry deadline atoms; no dedicated watchdog component. |
| D6 | CTM relationship | **DROPPED** — no shared abstraction with lifecycle reconciler. CTM stays as-is. |
| D7 | Test surface | **Reuse audit log.** Tag `CommandSource: OPERATOR | RECONCILER | INTERNAL`. Audit log is the observability surface for external management systems. |
| D8 | Migration of existing call sites | **Migrate kind-2 sites now.** Single ingress for state changes. |
| 1.1 | SYNCING → ON_DUTY trigger | **`readyCandidate` field on `ClusterSyncPong`.** Leader emits `ForceOnDuty` on receipt. |
| 1.2 | JOINING → SYNCING trigger | **Implicit.** JOINING node already in leader's ping topology; SYNCING begins when Rabia consumes snapshot. No explicit FSM trigger. |
| 1.3 | SYNCING timeout | **No auto-timeout.** Soft bounce JOINING ↔ SYNCING on quorum loss (in-memory only; KV stays JOINING throughout). |
| 1.4 | Busy-SYNCING protection | **Leader-side hold** scoped to active sync, scaled by snapshot bytes (clamped). Reconciler skips nodes with active holds. |
| 1.5 | Terminal state consolidation | **Collapse to STOPPED** + `StopReason { GRACEFUL, FORCED, DRAIN_FAILED }` sidecar. Pre-GA, no migration burden. |
