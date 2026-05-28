<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Membership Architecture v2 — Derive-from-Reality

**Status:** Foundation spec complete (8 scenarios settled). Implementation pending — see §13 migration plan.
**Branch:** `experimental/membership-redesign` (from `release-1.0.0-rc1` HEAD `b96619ea2`).
**Supersedes (when implemented):** the topology-management layer of `aether/docs/specs/membership-architecture-spec.md` — specifically the membership FSM, the slot-occupancy classifier, the reachability gates, the leader-pinned membership timers, and the drain coordinator's FSM integration. The **simple scheme at its base — SWIM, QUIC, Rabia, LeaderManager — is preserved unchanged**, and its reliability is the foundation this design builds on.
**Implementation target:** RC2 (or later). Not an RC1 patch.

---

## 1. Motivation

The current architecture has accumulated multiple overlapping authoritative sources for "is node N alive / in the cluster": SWIM gossip, consensus KV lifecycle (written by the leader-pinned FSM), QUIC transport, φ-accrual, and the slot-occupancy projection in CTM. These views drift intermittently, and the system reconciles them through a thicket of gates: `ReachabilityGate.isConfirmedUnreachable` (2-plane co-confirmation), `swimHealthGate`, the φ-accrual co-confirmation, quorum-safety guards, the `JoinDeadlineExpired` leader-only timer, the `occupantEpoch` slot fence, COLD_BOOT FAULTY suppression, the re-gated decommission cells, and others. The reducer has six lifecycle states × ~eight events with many nop cells where "we decided not to act here because of *that other gate*."

Every recurring bug class lives in the seams:
- **Leader-handoff dropping `JoinDeadlineExpired`** → flap re-stamp → stuck-at-3.
- **Bare `SwimHealthy → OnDuty` resurrection** of a vanished node (`ClusterMembershipReducer:129`).
- **Phantom `OnDuty` retention** of an Exited container (decommission veto), inflating CTM's `healthy` count.
- **`(Untracked, SwimHealthy) → OnDuty`** writing lifecycle from gossip alone.
- The `applyExternalLifecycleRemove` → `re-bind to PROVISIONING` re-stamp chain.

Each fix has exposed the next layer. The pattern is structural: **two sources of truth disagree, and the gate trying to reconcile them has a race.** The fix is not another gate — it's removing the parallel state that requires the gates in the first place.

Crucially, **cluster formation has been rock-solid for months** — no formation issues observed. Formation runs entirely on the simple scheme (SWIM→QUIC→Rabia→LeaderManager) with no FSM, no slot-occupancy classifier, no gates. Every bug above lives in the *parallel topology-management layer* built on top for topology-change scenarios. Removing that layer eliminates the bug class structurally and keeps the working part working.

## 2. Design principles

Four principles, in order of generality:

- **P1: Derive, don't duplicate.** The simple scheme (SWIM → QUIC → Rabia → LeaderManager) is authoritative. Any topology-change machinery *derives* from it; it does not run in parallel and require gates to keep it reconciled.
- **P2: Departure is observed silence — cause-agnostic at the membership layer.** A node that's silent on SWIM is gone. The membership layer does not need to know *why* the silence happened (crash, kill, drain, network). Cause lives upstream (operator command, application drain) and downstream (SWIM detection). The membership layer just reacts to silence.
- **P3: Drain is the unified self-shutdown procedure — trigger-agnostic.** Operator command, quorum loss, partial isolation, application overload — all converge on the same drain → stop-SWIM → exit path. The trigger lives upstream; the drain handler is one.
- **P4: Local derivation + leader-specific action.** Decision data (timers, observations) is derived continuously on every node from observable inputs. Only the *action* is leader-specific. Leader-handoff is structurally safe because every node has been running the same derivation locally — nothing transient is "in flight" to be lost.

### 2.1 Trust model

v2 places authority on **SWIM** (for discovery and converged failure detection) and **QUIC** (for local transport reality). It does so deliberately, because both have been the most reliable components of the system in production observation — and because their behaviors map exactly to what the membership layer needs ("what peers exist" and "which ones can I actually talk to").

This is a trade-off worth naming: if SWIM has a bug (e.g., gossip propagation defect, indirect-probe regression), v2 inherits it directly — there is no parallel detector to cross-check. The current architecture's gates partially defend against this by requiring multi-source corroboration, at the cost of the bug class this redesign is built to eliminate. v2 makes the opposite trade: trust SWIM, eliminate the gates. The decision rests on the empirical record (SWIM has been stable) and the principled observation (SWIM is *designed* to be the failure detector — using it as one is using it correctly).

If SWIM ever requires hardening, that work should land in SWIM directly (not as a parallel gate elsewhere).

## 3. Layer responsibilities

| Layer | Responsibility | Authority |
|---|---|---|
| **SWIM** | Peer discovery (via `ANNOUNCE` seeded by `PEERS`) + cluster-converged failure detection (gossip + indirect `ping-req`) | Cluster-consistent membership set |
| **QUIC** | Transport + local reachability ground truth | This node's own `connectedNodeCount` |
| **Rabia** | Consensus over connected peers against configured quorum size | Decided values |
| **LeaderManager** | Leader election + leader-pinned component activation (CDM, CTM, etc.) | Single "leader" identity (unchanged from current) |
| **CTM** (simplified) | Topology actuator: provisions / drains containers to track membership against configured size | Only on leader; only at quorum-safe moments |
| **NTT** (new) | Per-node tracker of pending departures with a local timeout; emits a local "topology unhealthy" notification | Local per-node; reaction is leader-specific |

What is **not** a layer here: no separate membership FSM, no slot-occupancy classifier, no 2-plane reachability gate, no φ-accrual detector. These exist in the current architecture and are removed (see §10).

## 4. The two counts (named distinctly in code)

The current code conflates two distinct counts under the umbrella of "node count," and that conflation is half of the confusion. The redesign names them explicitly and uses each at its proper site.

| Name | Source | Scope | Used for |
|---|---|---|---|
| **`localQuorumCount`** (local QUIC quorum count) | `QuicClusterNetwork.connectedNodeCount` (incl. self via +1) | Local per-node | Consensus liveness: *can this node reach enough peers to participate in a Rabia round?* Drives `QuorumStateNotification.ESTABLISHED` and quorum-loss-triggered self-drain. |
| **`clusterMembershipCount`** (cluster SWIM membership count) | SWIM's converged member set (after gossip + `ping-req`) | Cluster-consistent | Topology decisions: *is the cluster the right size against `configured`?* Drives CTM provisioning / reaping. |

Under full connectivity these counts agree. Under **partial connectivity they diverge** — and that divergence is the right signal: it says "the cluster is intact, but *this* node is locally isolated." A node whose `localQuorumCount` drops below threshold self-drains (safety); a divergence in `clusterMembershipCount` from `configured` triggers CTM provisioning or drain. **Calling both "node count" without qualification is forbidden in v2 code.**

## 5. Formation flow (PEERS-via-SWIM unification)

The flow is essentially what works today, with one structural change: **`PEERS` becomes SWIM's seed list only** — it does not feed QUIC's dial set directly.

1. Nodes boot with the static `PEERS` list.
2. SWIM treats `PEERS` as **seed addresses** and broadcasts `ANNOUNCE` (per current behavior post-ungate, started at transport-ready).
3. Other nodes' SWIM receive `ANNOUNCE` → emit discovery events (`JoinAnnounced` carrying the peer's `NodeInfo`).
4. **QUIC dials peers it learns about from SWIM discovery only.** The current `topologyManager` pre-population from static `coreNodes` is removed for QUIC dialing. (`ConnectionDirection.shouldInitiate` still gates which side initiates.)
5. QUIC connections form; per-node `localQuorumCount` climbs.
6. When `localQuorumCount ≥ N/2+1` → `QuorumStateNotification.ESTABLISHED` fires.
7. Rabia transitions `Stopped → Syncing → Idle`; consensus rounds available.
8. `LeaderManager` runs as a Rabia proposal; leader committed.
9. Leader-pinned components activate via existing `toggle*OnLeaderChange` wiring.

**Gains:** a single discovery → dial path. Cold boot, auto-heal of a fresh KSUID replacement, container restart with same NodeId, operator scale-up — all flow through the *same* mechanism (SWIM discovers → QUIC dials). The cold-boot-vs-auto-heal asymmetry that today makes auto-heal a special case is eliminated.

**Costs:** cold-boot quorum-establishment delayed by ~one SWIM round (≤1s with current `ANNOUNCE` cadence). SWIM is critical-path for bootstrap (acceptable given its reliability; SWIM uses its own UDP transport, not QUIC, so no circular dependency).

## 6. NTT — Node Topology Tracker

The single new component. NTT replaces the leader-pinned `JoinDeadlineExpired` timer *and* the FSM lifecycle states whose only job was to remember "we have a timer pending." It is small, per-node, with a precise contract.

### 6.1 Contract
- **Runs on every node** in the cluster — universal, never leader-pinned.
- **Inputs:**
  - SWIM converged departure notifications (`FAULTY` / `Departed` — post-gossip and post-`ping-req`; **not** local `SUSPECT` or direct-probe failure).
  - QUIC reconnect events.
- **State (per departed peer):** a single in-memory `Deadline` (an instant). No persisted KV.
- **Output:** local in-process notification `TopologyUnhealthy(peerId)`.

### 6.2 Behavior
- On SWIM `Departed(peer)` → start the NTT timer for `peer`.
- On QUIC reconnect to `peer` → cancel the timer. *Note:* SWIM `Healthy` alone does NOT cancel — only an actually reconnected QUIC channel does. This is what filters SWIM lies (stale gossip about a vanished container) from real resurrections.
- On timer expiry → emit `TopologyUnhealthy(peer)` locally.

### 6.3 Reaction
- The leader's CTM listens to `TopologyUnhealthy`. On the event, CTM checks the quorum-safety predicate at the action site: *if I provision a replacement, will the resulting cluster still preserve confirmed-healthy quorum?* If yes, provision. If no (sub-quorum), do nothing — let self-drain handle dissolution.
- Non-leader nodes ignore the event (or use it for observability only).

### 6.4 Why this fixes the leader-handoff bug class structurally
The current `JoinDeadlineExpired` mechanism is a *one-shot event sent to the leader*. If the leader changes during the timeout window, the event is delivered to a non-leader and dropped (single-writer no-op). State is lost.

NTT replaces transient leader-targeted events with **continuous local state derived from observable inputs**. The decision data (the pending timer) lives on every node identically. The action (CTM provisioning) is leader-specific, but the moment-of-action check ("am I leader? is it quorum-safe?") happens at the action site, not at event-generation time. Leader handoff during the timeout window is harmless: the new leader has been running the same timer locally; nothing is lost.

This pattern (P4) is broadly applicable. Other leader-pinned timers (drain ack, join timeout, sync timeout, slot FILLING expiry) all collapse into this shape or disappear entirely.

## 7. CTM, simplified

CTM today is a ~1700-line slot state machine with `HEALTHY/FILLING/DEAD/EMPTY` classification, two reclaimers, the `occupantEpoch` fence, and `supersededNodeId` lineage. Under v2, CTM becomes a small reactor over derived state.

### 7.1 Inputs
- `configured` cluster size (from `ClusterConfigValue.coreCount`, KV-subscribed).
- `clusterMembershipCount` (SWIM-converged member set, cluster-consistent — §4).
- `TopologyUnhealthy` events from local NTT.
- `DrainRequestKey(nodeId) = pending` writes (operator drain commands for specific nodes — §8).

### 7.2 Behavior (leader only)
- **Underprovisioned** (`clusterMembershipCount < configured`): on `TopologyUnhealthy` (or directly on observing the shortfall after a configured-size increase), if quorum-safety holds → provision the difference, KSUID-named, with PEERS seeded from current cluster members.
- **Overprovisioned** (`clusterMembershipCount > configured` — e.g., a previously-departed node returns after a replacement is online, or the operator scaled down): initiate graceful drain of the excess by writing `DrainRequestKey(targetNodeId) = pending`. Selection heuristic: **newest-joined-first** by default; operator-configurable. Sequenced (not parallel) to maintain quorum throughout the shrink.
- **Drain target acknowledgement:** none required at the CTM layer — once `DrainRequestKey` is written, the target node observes it and drives the rest (§8); CTM just waits for `clusterMembershipCount` to converge.

### 7.3 What CTM stops doing
- No slot KV records with `occupantEpoch` / `supersededNodeId`.
- No `FILLING` / `DEAD` / `EMPTY` slot classification.
- No `freeStaleFillingSlots` / `freeDeadSlots` reclaimers.
- No FILLING deadline tracking.
- No `JoinDeadlineExpired` event emission (NTT replaces).
- No parallel-FSM state to reconcile.

Slots become **positions, not records**: there are `configured` positions; each is occupied iff a member is in the SWIM-converged membership set; the count of empties is `configured − clusterMembershipCount`. No per-slot state to maintain.

## 8. Drain — the unified self-shutdown procedure

Drain is a node-local procedure with a small set of triggers. The membership layer does not have a drain state machine; the membership-layer effect of any drain is identical to abrupt departure (observed silence).

### 8.1 Triggers (all paths converge here)
- **Operator scale-down** (Case A — configured size decreases): node observes it is no longer in the configured set (or is selected for shrink) → trigger drain.
- **Operator specific drain** (Case B — `DrainRequestKey(self) = pending`): node observes the request on its own KV key → trigger drain.
- **Quorum-loss self-drain (safety):** node observes its `localQuorumCount` is below threshold for ≥ `quorumLossDrainThreshold` (§14) → trigger drain.
- **Partial isolation:** is the same as quorum-loss self-drain (it's *how* a partially-isolated node observes its local quorum failing); no separate trigger.
- **Application overload / planned restart** (out of scope for this spec, but the unified path supports it).

### 8.2 The procedure (one procedure, regardless of trigger)
1. **Stop accepting new work** (application layer). Existing in-flight work proceeds.
2. **Drain in-flight** (application layer — slice migration, request completion, etc.). Outside this spec's scope.
3. **Stop SWIM probes.** Once application drain is complete. *Order matters:* peers must continue routing during the drain window; only when application work is done should the node go silent.
4. **Exit with `Runtime.halt(2)`.** Distinguishes self-drained exits from clean stop (0), SIGKILL (137), SIGTERM (143) for operator/test observability.

From the cluster's perspective, step 3 *is* the departure signal — SWIM detects silence, converges on `Departed`, NTT fires, CTM reacts per §7.2. **There is no separate "voluntary LEAVE" SWIM message in v2.** Silence is the universal departure signal.

### 8.3 Uninterruptibility (when applicable)
- **Quorum-loss-triggered drain is uninterruptible** (I9). Once started, it does not abort even if quorum returns mid-drain. Reasons: prevents oscillation (lose quorum → start drain → quorum returns → cancel → repeat) and prevents serving stale data after re-joining without re-sync.
- **Operator-initiated drain may be cancellable** (operator decision). If cancelled mid-drain: node resumes SWIM probes; if SWIM had marked the node `SUSPECT`/started gossip, the resumed probes flip it to `ALIVE`; if NTT timer had started on peers, QUIC reconnect cancels it (per I7). Clean recovery.

### 8.4 Operator visibility
A drain-progress field MAY be written to a dedicated KV key by the draining node (e.g., `DrainProgressKey(nodeId)`) for operator polling. This is optional, application-layer concern — *not* membership state. Membership convergence is observable via the standard endpoints (`/api/cluster/topology`, `/api/cluster/generation`).

**Caveat:** drain-progress publishing requires consensus, so it is available for operator-initiated drains (§8.1 first two triggers) but **not** for quorum-loss-triggered drain (§8.1 third trigger) — by definition, consensus is unavailable in that case. The universal observability signal for any drain is the `Runtime.halt(2)` exit code (§8.2 step 4); peers observe departure via SWIM regardless of KV publishing.

### 8.5 Drain request KV record
The membership-layer mechanism by which operators target a specific node for drain (Case B in §12.4, and explicit-target scale-down in §12.8) is a single KV record:

```
DrainRequestKey(nodeId)  →  DrainRequestValue {
    requestedAtHlc: HlcTimestamp,
    requestedBy:    Option<OperatorId>
}
```

- `requestedAtHlc`: HLC of the requesting node at the moment the operator command was processed. HLC (already used throughout for causal ordering) is preferred over wall-clock millis so the timestamp interleaves correctly with other KV writes — including the configured-size change in an R1-atomic operator command.
- `requestedBy`: optional operator identity for audit; not load-bearing for the membership-layer behavior.

The targeted node subscribes to `DrainRequestKey(self)` via the standard KV-notification path at startup. On observing a `pending` request, it enters the §8 drain procedure.

CTM does not act on `DrainRequestKey` directly; it only reacts to the resulting `clusterMembershipCount` change (and the configured-size change, if any, that was atomically co-committed). The record may be cleaned up post-drain (the node is gone; the request was fulfilled) — cleanup mechanism is an implementation detail (e.g., a small GC observer on `Departed` events, or just left as audit). No `completed` field is required.

## 9. Design rules

- **R1: Atomic operator commands.** Operator commands that combine multiple consensus writes (e.g., drain + configured-size decrease) MUST commit atomically — single multi-put through consensus, or none at all. Sequential writes admit small race windows where CTM's reaction is non-deterministic.
- **R2: Quorum-safety on configured-size change.** A `coreCount` reduction that would put the cluster below quorum (or below an operator-set minimum) is **rejected at the operator API / consensus-write validation** — action-site validation, not a parallel state machine. Same principle as CTM's "don't provision below quorum" guard.
- **R3: Drain sequencing on scale-down.** When CTM drains multiple nodes for a scale-down, drains MUST be sequenced (not parallel), each waiting for `clusterMembershipCount` convergence before initiating the next, to maintain quorum throughout the transition.

## 10. What this replaces — explicit deletion list

For implementation traceability, the following are removed (entire modules / classes / mechanisms):

| Removed | Reason |
|---|---|
| `MembershipFsm` + lifecycle states `Untracked` / `Provisioning` / `Joining` / `OnDuty` / `Draining` / `Stopped` | State derived from SWIM + QUIC, not maintained |
| `ClusterMembershipReducer` | Pure-function reducer over the deleted lifecycle |
| `ReachabilityGate.isConfirmedUnreachable` (2-plane gate) | SWIM convergence does this natively |
| φ-accrual detector | SWIM is the detector |
| `JoinDeadlineExpired` event + leader-pinned timer | NTT replaces |
| Slot KV records (`ProvisioningSlotValue` with `occupantEpoch`/`supersededNodeId`) | Slots become count-derived positions |
| `freeStaleFillingSlots` / `freeDeadSlots` / FILLING expiry | No FILLING state to reclaim |
| `applyExternalLifecycleRemove` → `applyLifecycleRemoveWithSlot` re-bind chain | No parallel lifecycle to remove |
| `(Untracked, SwimHealthy) → untrackedDirectToOnDuty` reducer cell (the resurrection vector) | No parallel lifecycle to write |
| Static-PEERS pre-population of `topologyManager` for QUIC dialing | SWIM is the sole input (§5) |
| `Draining` lifecycle state + `DrainCoordinator` ↔ membership-FSM integration + `awaitDrainAck` as an FSM transition | Drain is node-local + KV-observable (§8) |
| `SelfDrainCoordinator` as an FSM-integrated component | Replaced by a small local quorum observer that triggers §8 |
| `DecommissionedAtomGc` | No lifecycle atoms to GC (subject to confirmation during implementation) |
| SWIM voluntary `LEAVE` message handling | Silence is the universal departure signal (P2) |

What remains: SWIM, QUIC, Rabia, `LeaderManager` (all unchanged), simplified CTM (§7), NTT (§6), per-node `LocalQuorumWatcher` (small observer that drives §8's quorum-loss trigger), `DrainRequestKey` KV record (operator drain commands).

## 11. Invariants

- **I1:** SWIM is THE discovery + converged-failure-detection layer. No parallel detector.
- **I2:** QUIC `connectedNodeCount` is THE consensus-liveness oracle. No parallel transport-state.
- **I3:** Topology decisions are derived from SWIM membership; they are not maintained as separate state.
- **I4:** NTT runs on every node and emits `TopologyUnhealthy` locally; only the leader's CTM acts on it.
- **I5:** Sub-quorum action is blocked at the action site (quorum-safety guard inside CTM, R2 for operator API).
- **I6:** `PEERS` is SWIM's seed list only. QUIC dials exclusively from SWIM-discovered peers.
- **I7:** QUIC reconnect is the sole resurrection signal for NTT. SWIM `Healthy` alone (without an actually reconnected channel) cannot cancel NTT's timer.
- **I8:** The two counts (`localQuorumCount` vs `clusterMembershipCount`) are named distinctly in code. No "node count" without qualification.
- **I9:** Quorum-loss-triggered drain is uninterruptible.
- **I10:** False-positive drain is preferable to false-negative split-brain. The `quorumLossDrainThreshold` window tunes the trade.
- **I11:** Drain progress is node-local + KV-observable; it is **not** a cluster-wide FSM state.

## 12. Settled scenarios

### 12.1 Initial formation (cold boot)
Per §5. SWIM-discovery + QUIC-dial + Rabia-quorum + leader-election. No FSM, no slot records, no gates. Warm boot (containers restart against an existing `pgdata`) is the same flow; existing consensus state is loaded by Rabia naturally.

### 12.2 Abrupt node departure + auto-heal
1. Node X dies. SWIM probes fail; gossip propagates; SWIM converges on `Departed(X)` cluster-wide.
2. On each node, NTT receives the converged `Departed(X)` → starts a local timer.
3. If X does not QUIC-reconnect within `nttDepartureTimeout` (§14) → every node's NTT emits `TopologyUnhealthy(X)`.
4. The leader's CTM observes the event, checks quorum-safety, and if safe, provisions a replacement container R (KSUID-named, PEERS-seeded).
5. R boots, SWIM-announces, peers SWIM-discover R, QUIC dials R, R joins consensus via the formation flow.
6. `clusterMembershipCount` returns to `configured`; CTM is satisfied.

**Leader-handoff during steps 2–4 is harmless** (P4): the new leader has been running the same NTT timer locally; the action picks up at the new leader's action site.

### 12.3 Asymmetric partial visibility
Per §4. A node C that cannot reach peer X (while A, B can):
- SWIM converges on X-alive across all nodes via gossip + indirect `ping-req`. NTT does not fire on any node.
- C's `localQuorumCount` is reduced; if it falls below threshold, C self-drains via §8.5a path (existing safety mechanism); cluster continues without C.
- CTM (on the leader, which has a healthy view) sees `clusterMembershipCount = configured` → no action.

**Residual edge:** a leader with sustained partial visibility AND above local quorum AND unable to receive corrective gossip would fire false NTT and attempt to over-provision. Self-correction: the leader's local quorum eventually fails as it loses more reachability → self-drains; or leader handoff to a consistent-view node. Over-provision is recovered via §7.2's drain of excess. Accepted as a small bounded edge, not separately defended.

### 12.4 Graceful node decommission (operator-initiated)
Two cases, **distinguished entirely by the configured-size change**, not by a flag or separate FSM state:

- **Case A — scale-down:** operator atomically writes `{coreCount = N-1; DrainRequestKey(X) = pending}` (R1). Node X observes its own drain request → runs §8 procedure → exits. SWIM converges on departure; NTT fires; CTM sees `clusterMembershipCount = configured` → no-op.
- **Case B — specific replacement (size unchanged):** operator writes `DrainRequestKey(X) = pending`. Node X drains → exits. CTM sees `clusterMembershipCount < configured` → provisions a fresh KSUID replacement (§7.2 underprovisioned path).

The membership layer is unaware of the distinction; the configured-size change at the moment of CTM's reaction determines the behavior. The `Draining` lifecycle state and `awaitDrainAck` FSM transition are deleted (§10).

### 12.5 Quorum loss → self-drain
A node's `LocalQuorumWatcher` observes `localQuorumCount < N/2+1` continuously for ≥ `quorumLossDrainThreshold` (§14). On commit, the node enters §8 procedure (uninterruptible, I9). Exit with `halt(2)` (I11 / operator visibility).

**Whole-cluster cascade is the correct safety behavior:** if the cluster has wholly lost quorum, every node observes its own local quorum failing → each self-drains → cluster goes down. Better dead than split-brain.

**Recovery:** containers exit; restart per Docker policy; on restart, normal cold-boot formation flow (§12.1). Operator chooses restart policy.

### 12.6 Partition heal
After a partition with self-drained minority: minority containers exited per §12.5; majority continued (possibly with NTT-driven KSUID replacements bringing `clusterMembershipCount` back to `configured`).

When the partition heals and minority containers restart (per their restart policy):
1. Each minority container does cold-boot formation, SWIM-discovers the running majority + each other, QUIC dials.
2. If majority had provisioned replacements: returning originals push `clusterMembershipCount > configured` → CTM drains excess per §7.2 (newest-joined-first by default).
3. If majority had NOT provisioned (e.g., sub-quorum prevented provisioning): returning originals fill the gap; CTM reaches `clusterMembershipCount = configured`.

No special "partition heal" code path; it composes from §12.7 + §7.2's overprovision drain.

### 12.7 Container restart with same NodeId
A node's container restarts (any cause — crash, kill, operator restart, `halt(2)` followed by Docker auto-restart):

- **Within NTT window:** QUIC reconnect cancels the timer (I7). No replacement provisioned. The restart is invisible to membership beyond the brief QUIC disconnect/reconnect.
- **After NTT fired + CTM provisioned a replacement:** returning original brings `clusterMembershipCount > configured` → over-provision drain of excess.
- **After NTT fired but quorum-safety blocked the provision:** returning original helps restore quorum; CTM may now safely provision if still short, or `clusterMembershipCount = configured` is reached.

The "same NodeId" property is mildly convenient (existing SWIM gossip entries are confirmed by QUIC reconnect) but does not drive special behavior. v2 does not distinguish "same NodeId returning" from "fresh NodeId joining" — both flow through SWIM-discovery → QUIC-dial.

### 12.8 Operator scale up/down
- **Scale up:** operator writes `coreCount = N + k`. CTM observes `clusterMembershipCount < configured` → provisions `k` new KSUID-named containers (§7.2 underprovisioned path). Quorum-safe by definition.
- **Scale down:** operator atomically writes `{coreCount = N - k; DrainRequestKey(...) = pending}` for `k` selected nodes (default newest-joined-first; operator-overridable), R1 atomic, R2 quorum-safety pre-checked, R3 sequenced. Each drained node follows §12.4 Case A. CTM observes convergence.

## 13. Migration plan

Migration is staged so the existing system continues to work throughout. The eventual cutover is one step, but verification at each stage is per-scenario.

- **E1.** Introduce `NTT` and `LocalQuorumWatcher` alongside the existing membership FSM. Wire `NTT` to SWIM converged departure; verify `TopologyUnhealthy` emission against observed departures. **No action wired** — observation only.
- **E2.** Wire CTM's auto-heal to `TopologyUnhealthy` as the *primary* trigger; leave the existing FSM/slot pathway as a redundant trigger for comparison. Add the `clusterMembershipCount` derived from SWIM-converged set; add `localQuorumCount` as a renamed metric. Migrate code to use named counts (I8).
- **E3.** Run the chaos suite with NTT as primary; confirm equivalence-or-better against FSM+slot path. Iterate `nttDepartureTimeout` and `quorumLossDrainThreshold` defaults.
- **E4.** Cut over to NTT-only. Delete the membership FSM, the slot KV records, the gates, φ-accrual, the resurrection cell, the supporting machinery (§10 deletion list).
- **E5.** Remove static-PEERS pre-population of `topologyManager` for QUIC dialing; switch QUIC to SWIM-discovery as sole input (§5 + I6).
- **E6.** Replace `DrainCoordinator`'s FSM-integrated drain with the §8 unified procedure + `DrainRequestKey`. Delete `Draining` lifecycle state, `awaitDrainAck` as an FSM transition, `SelfDrainCoordinator` as an FSM-integrated component.

Each stage is independently verifiable (unit tests for the new components in E1; equivalence comparison in E2–E3; full chaos suite green at E4; full chaos suite green again at E5; full chaos suite green at E6). The implementation can pause at any boundary if regressions appear.

**Expected scope:** multi-week implementation effort. The migration shrinks the codebase substantially — removing ~1700 lines of CTM slot machinery, the entire FSM module, the gates, and supporting infrastructure.

## 14. Open questions / tunables

Defaults proposed; refine during implementation against the chaos suite.

- **`nttDepartureTimeout`** — how long NTT waits before emitting `TopologyUnhealthy`. Proposed default: **15s**. Trade-off: transient-blip tolerance vs recovery latency. Long enough to absorb SWIM convergence (~5s) + brief network glitches; short enough that auto-heal feels prompt.
- **`quorumLossDrainThreshold`** — how long local quorum must stay below threshold before quorum-loss-triggered drain commits. Proposed default: **8s** (preserves current S19 row). Tunes I10's trade.
- **Scale-down drain selection heuristic** — which excess nodes to drain. Default: **newest-joined-first** (preserves older, more-stable nodes; operator override available).
- **Replacement-flap S01 budget alignment** — the current S01 test row asserts a replacement killed in its JOINING window is decommissioned within 25s. v2's path (SWIM departed → NTT timer) is slower — `~5s + nttDepartureTimeout`. Either tighten NTT specifically for replacements still in their join window, or relax the S01 budget assertion. Decide during E3.
- **Configuration mismatch fail-fast** — different nodes booting with different `coreCount` or `PEERS` lists should fail-fast (refuse to start) rather than silently misbehave. Boot-time validation, not a runtime concern.
- **`MembershipDecision` event stream replacement** — components subscribing to today's "node added/removed" events (CDM, control loops, routing) need a clean emission point in v2. Proposal: a thin observer over SWIM convergence + configured-size changes, emitted from CTM. Interface spelled out during E2.
- **Configured-size change observation race.** A `coreCount` change is consensus-replicated, so different nodes observe it at slightly different times (sub-second window during commit propagation). `LocalQuorumWatcher` uses the *current* observed `configured` to compute threshold — so during the window, two nodes might briefly compute quorum against different N. Idempotent and self-correcting (the change propagates within consensus-commit time, well inside `quorumLossDrainThreshold`), but worth being explicit so it doesn't surprise implementation.

## 15. Out of scope

Explicitly delimited so the boundary is clear:

- **Slice migration / in-flight request handling during drain** — application layer.
- **Operator API / CLI surface, observability / metrics** — separate concerns; the spec only requires that `DrainRequestKey`, `coreCount`, and SWIM membership are KV-observable.
- **Cert rotation, IP rebinding for cloud / mobile environments** — transport-layer / deployment.
- **Rolling upgrades / mixed-version clusters** — deployment strategy.
- **`LeaderManager` internals** — preserved unchanged.
- **HLC clock semantics, Rabia consensus protocol details** — preserved unchanged.

## 16. References

- Predecessor: `aether/docs/specs/membership-architecture-spec.md` (the layered model being superseded for topology management; the simple scheme at its base — SWIM, QUIC, Rabia — is preserved).
- Discovery: `aether/docs/specs/swim-driven-topology-spec.md`.
- Current convergence (to be retired): `aether/docs/specs/slot-based-membership-convergence-spec.md`.
- Layered-stack diagnosis (root-cause of the bug class this redesign eliminates): `aether/docs/internal/progress/session-handover-2026-05-28.md`.
- Session handover for this design work: `aether/docs/internal/progress/session-handover-2026-05-28-experimental.md`.
- Memory: `[[project_cluster_b_wedge_layered_stack]]`, `[[project_membership_v2_redesign]]`.

## 17. Changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-28 | session author | Initial DRAFT — scenarios 1, 2/2a, 5 captured; pending 3, 3a, 5a, 5b, 6. |
| 2026-05-28 | session author | Foundation spec finalized — all 8 scenarios settled. Added P2 (departure = silence), P3 (unified drain), R1–R3 design rules, I9–I11 invariants, drain section §8, full §12 scenario coverage, §14 tunable defaults, §15 out-of-scope. |
| 2026-05-28 | session author | Final refinements: §2.1 trust model, §8.4 quorum-loss-drain consensus-unavailable caveat, §8.5 `DrainRequestKey` schema (HLC-timestamped), §14 configured-size observation race note. |
