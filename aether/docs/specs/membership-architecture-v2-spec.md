<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Membership Architecture v2 — Derive-from-Reality (DRAFT)

**Status:** DRAFT. Foundation laid (scenarios 1, 2/2a, 5). Pending scenarios (3, 3a, 5a, 5b, 6) will refine details but are expected to leave the core intact.
**Branch:** `experimental/membership-redesign` (off `release-1.0.0-rc1` HEAD `b96619ea2`).
**Supersedes (when complete):** the topology-management layer of `membership-architecture-spec.md` — specifically the membership FSM, the slot-occupancy classifier, the reachability gates, and the leader-pinned membership timers. The simple scheme it sits on (SWIM, QUIC, Rabia, LeaderManager) is preserved unchanged.
**Implementation target:** future session — this document captures the agreed design, not the implementation.

---

## 1. Motivation

The current architecture has accumulated multiple overlapping authoritative sources for "is node N alive / in the cluster": SWIM gossip, consensus KV lifecycle (written by the leader-pinned FSM), QUIC transport, φ-accrual, and the slot-occupancy projection in CTM. These views drift intermittently, and the system reconciles them through a thicket of gates: `ReachabilityGate.isConfirmedUnreachable` (2-plane co-confirmation), `swimHealthGate`, the φ-accrual co-confirmation, quorum-safety guards, the `JoinDeadlineExpired` leader-only timer, the `occupantEpoch` slot fence, COLD_BOOT FAULTY suppression, the re-gated decommission cells, and others. The reducer's transition table has six lifecycle states × ~eight events with many nop cells where "we decided not to act here because of *that other gate*."

Every recurring bug class lives in the seams between gates:
- **Leader-handoff dropping `JoinDeadlineExpired`** → flap-re-stamp → stuck-at-3 (this session).
- **Bare `SwimHealthy → OnDuty` resurrection** of a vanished node (`ClusterMembershipReducer:129`).
- **Phantom `OnDuty` retention** of an Exited container (decommission veto), inflating CTM's `healthy` count.
- **`(Untracked, SwimHealthy) → OnDuty`** writing lifecycle from gossip alone.
- The `applyExternalLifecycleRemove` → `re-bind to PROVISIONING` re-stamp chain.

Each fix has exposed the next layer; see `session-handover-2026-05-28.md` for the layered-stack diagnosis. The pattern is structural: **two sources of truth disagree, and the gate trying to reconcile them has a race.** The fix is not another gate — it's removing the parallel state that requires the gates in the first place.

## 2. Design principle

> **Derive, don't duplicate.** The simple scheme (SWIM → QUIC → Rabia → LeaderManager) stays untouched and authoritative. Any topology-change machinery must *derive* from this scheme — not run in parallel and require gates to keep it reconciled.

The simple scheme has been rock-solid for cluster formation (no formation issues observed in months). The bugs live exclusively in the *parallel topology-management layer* (membership FSM, slot-occupancy classifier, gates). Removing that layer eliminates the bug class structurally.

## 3. Layer responsibilities (preserved + clarified)

| Layer | Responsibility | Authority |
|---|---|---|
| **SWIM** | Peer discovery (via `ANNOUNCE` seeded by PEERS) + cluster-converged failure detection (gossip + indirect `ping-req`) | Cluster-consistent membership set |
| **QUIC** | Transport + local reachability ground truth | This node's own `connectedNodeCount` |
| **Rabia** | Consensus over connected peers against configured quorum size | Decided values |
| **LeaderManager** | Leader election + leader-pinned component activation (CDM, CTM, etc.) | Single "leader" identity |
| **CTM** (simplified) | Topology actuator: provisions/reaps containers to track cluster membership against configured size | Only when leader; only at quorum-safe moments |
| **NTT** (new) | Per-node tracker of pending departures with a local timeout; emits a local "topology unhealthy" notification | Local per-node; reaction is leader-specific |

What is **not** a layer here: there is no separate membership FSM, no slot-occupancy classifier, no 2-plane reachability gate, no φ-accrual detector. These exist in the current architecture and are removed.

## 4. The two counts (the core conceptual clarification)

The current code conflates two distinct counts under the umbrella of "node count," and that conflation is half of the confusion. The redesign names them explicitly:

| Name | Source | Scope | Used for |
|---|---|---|---|
| **Local QUIC quorum count** | This node's `QuicClusterNetwork.connectedNodeCount` (incl. self via +1) | Local per-node | Consensus liveness: *can I reach enough peers to participate in a Rabia round?* Drives `QuorumStateNotification.ESTABLISHED` and self-drain on quorum loss. |
| **Cluster SWIM membership count** | SWIM's converged member set (after gossip + ping-req) | Cluster-consistent | Topology decisions: *is the cluster the right size against the configured size?* Drives CTM provisioning/reaping. |

Under full connectivity these counts agree. Under **partial connectivity they diverge** — and that divergence is the right signal: it says "the cluster is intact, but *this* node is locally isolated." A node whose local QUIC quorum drops below threshold self-drains (safety); a node whose SWIM membership shows a missing peer triggers CTM provisioning.

This naming MUST be carried through to code; calling both "node count" is a primary source of the current confusion.

## 5. Formation flow (preserved, with PEERS-via-SWIM unification)

The flow is essentially what works today, with one change: **PEERS becomes SWIM's seed list only** — it no longer feeds QUIC's dial set directly.

1. Nodes boot with the static PEERS list.
2. SWIM treats PEERS as **seed addresses** and broadcasts `ANNOUNCE`.
3. Other nodes' SWIM receive `ANNOUNCE` → emit discovery events (`JoinAnnounced` carrying the peer's `NodeInfo`).
4. **QUIC dials peers it learns about from SWIM discovery only**. The current `topologyManager` pre-population from static `coreNodes` is removed. (`connectionDirection.shouldInitiate` still gates which side initiates.)
5. QUIC connections form; per-node `connectedNodeCount` climbs.
6. When `connectedNodeCount + 1 ≥ N/2+1` → `QuorumStateNotification.ESTABLISHED` fires.
7. Rabia transitions `Stopped → Syncing → Idle`; consensus rounds available.
8. `LeaderManager` runs as a Rabia proposal; leader committed.
9. Leader-pinned components (CDM, CTM, …) activate on the new leader via existing `toggle*OnLeaderChange` wiring.

**What this gains:** a single discovery → dial path. Cold boot, auto-heal of a fresh KSUID replacement, container restart, and operator scale-up all flow through the *same* mechanism (SWIM discovers → QUIC dials). The dual-path asymmetry (static-PEERS dial vs SWIM-`JoinAnnounced` dial) that currently makes auto-heal a special case is eliminated.

**What this costs:** cold-boot quorum-establishment is delayed by ~one SWIM round (≤1s with current `ANNOUNCE` cadence). SWIM is critical-path for bootstrap (acceptable given its reliability; SWIM uses its own UDP transport, not QUIC, so there is no circular dependency).

## 6. NTT — Node Topology Tracker

The single new component. NTT replaces the leader-pinned `JoinDeadlineExpired` timer + the FSM lifecycle states whose only job was to remember "we have a timer pending." It is a small, per-node component with a precise contract:

### 6.1 Contract
- **Runs on every node** in the cluster — universal, never leader-pinned.
- **Inputs:**
  - SWIM converged departure notifications (`FAULTY` / `Departed` — post-gossip and post-`ping-req`, **not** local `SUSPECT` or direct-probe failure).
  - QUIC reconnect events.
- **State (per departed peer):** an unstarted, running, or cleared local timer (a single `Deadline`, an instant). No persisted KV.
- **Outputs (local in-process notification):** `TopologyUnhealthy(peerId)` event.

### 6.2 Behavior
- On SWIM `Departed`(peer) → start NTT timer for `peer`.
- On QUIC reconnect to `peer` (authoritative resurrection signal) → cancel the timer. *Note:* SWIM `Healthy` alone does NOT cancel — only an actual reconnected QUIC channel does. SWIM lies (stale gossip about a vanished container) cannot revive the timer.
- On timer expiry → emit `TopologyUnhealthy(peer)` locally.

### 6.3 Reaction
- The leader's CTM listens to `TopologyUnhealthy`. On the event, CTM checks the quorum-safety predicate: *if I provision a replacement, will the resulting cluster still preserve confirmed-healthy quorum?* If yes, provision. If no (sub-quorum), do nothing — let self-drain handle dissolution.
- Non-leader nodes ignore the event (or use it for observability only).

### 6.4 Why this fixes the leader-handoff bug class structurally
The current `JoinDeadlineExpired` mechanism is a *one-shot event sent to the leader*. If the leader changes during the timeout window, the event is delivered to a non-leader and dropped (single-writer no-op). State is lost.

NTT replaces transient leader-targeted events with **continuous local state derived from observable inputs**. The decision data (the pending timer) lives on every node identically. The action (CTM provisioning) is leader-specific, but the moment-of-action check ("am I leader? is it quorum-safe?") happens at the action site, not at event-generation time. Leader handoff during the timeout window is harmless: the new leader has been running the same timer locally; nothing is lost.

This pattern — **derive the decision data on every node from observable state; gate only the action by leader role** — is broadly applicable. Other leader-pinned timers (drain ack, join timeout, sync timeout, slot FILLING expiry) all collapse into this shape.

## 7. CTM, simplified

The current `ClusterTopologyManagerRecord` is a ~1700-line slot state machine with `HEALTHY/FILLING/DEAD/EMPTY` classification, `freeDeadSlots`/`freeStaleFillingSlots` reclaimers, the `occupantEpoch` fence, and `supersededNodeId` lineage tracking. Under this redesign, CTM becomes a much smaller reactor over derived state:

### 7.1 What CTM tracks
- Configured cluster size (from `ClusterConfigValue.coreCount`).
- Current cluster membership count (from SWIM-converged view, cluster-consistent).
- Pending NTT-emitted `TopologyUnhealthy` events.

### 7.2 What CTM does (on the leader only)
- On `TopologyUnhealthy(peer)`: if `membership_count < configured` and quorum-safety holds → provision a replacement container (KSUID-named, seeded with PEERS).
- On configured-size change (operator scale up/down — TBD in scenario 3a): provision additional or initiate graceful drain of excess.
- On `membership_count > configured` (over-provision detected — e.g., a previously-departed node returns after a replacement is online): initiate graceful drain of the excess (newest-first by default).

### 7.3 What CTM stops doing
- No slot KV records with `occupantEpoch` / `supersededNodeId`.
- No `FILLING` / `DEAD` / `EMPTY` slot classification.
- No `freeStaleFillingSlots` / `freeDeadSlots` reclaimers.
- No FILLING deadline tracking.
- No `JoinDeadlineExpired` event emission (NTT replaces).
- No provisioning circuit breaker bookkeeping that tries to reason about the parallel FSM state.

Slots become **positions, not records**: there are `configured` positions; each is occupied iff a member is connected on QUIC; the count of empties is `configured - membership_count`.

## 8. What this replaces — explicit deletion list

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
| Static-PEERS pre-population of `topologyManager` for QUIC dialing | SWIM is the sole input |
| `DecommissionedAtomGc` | No lifecycle atoms to GC (subject to confirmation in scenario 3) |

## 9. Invariants

- **I1: SWIM is THE discovery and converged-failure-detection layer.** No parallel detector.
- **I2: QUIC `connectedNodeCount` is THE consensus-liveness oracle.** No parallel transport-state.
- **I3: Topology decisions are derived from SWIM membership; they are not maintained as separate state.** No parallel topology FSM.
- **I4: NTT runs on every node and emits `TopologyUnhealthy` locally; only the leader's CTM acts on it.** Decision data is universal; action is leader-specific.
- **I5: Sub-quorum action is blocked at the action site (quorum-safety guard inside CTM).** Not via a parallel state machine.
- **I6: PEERS is SWIM's seed list only.** QUIC dials exclusively from SWIM-discovered peers.
- **I7: QUIC reconnect is the sole resurrection signal for NTT.** SWIM `Healthy` alone (without an actual reconnected channel) cannot revive a departing peer.
- **I8: The two counts (local QUIC quorum vs cluster SWIM membership) are named distinctly in code.** No "node count" without qualification.

## 10. Scenarios — settled

### 10.1 Initial formation (cold boot)
Per §5. The formation flow is what works today, with the PEERS-via-SWIM unification. No FSM, no slot records, no gates involved. The cluster reaches operational state by SWIM discovery → QUIC connect → Rabia quorum → leader election.

### 10.2 Abrupt node departure + auto-heal
1. Node X dies (SIGKILL, crash, network drop).
2. SWIM probes from peers to X fail; gossip propagates; eventually SWIM converges on `Departed(X)` cluster-wide.
3. On each node, NTT receives the converged `Departed(X)` → starts a local timer for X.
4. (Timer interval is a single tunable: `nttDepartureTimeout`. Long enough to absorb transient blips; short enough for prompt recovery.)
5. If X does not QUIC-reconnect within the timeout → every node's NTT emits `TopologyUnhealthy(X)`.
6. The leader's CTM observes the event, checks quorum-safety, and if safe, provisions a replacement container R.
7. R boots with PEERS, SWIM-announces, SWIM converges on R-alive, QUIC dials R, R joins consensus (existing formation-style flow).
8. Cluster's `membership_count` returns to `configured`; CTM is satisfied.

**Property:** if leader changes between step 3 and step 5, the new leader has been running the same NTT timer locally — the new leader's CTM picks up the action at step 6. No event is lost.

### 10.3 Asymmetric partial visibility
Per §4 (the two counts). A node C that cannot reach peer X (while A and B can):
- SWIM converges on X-alive across all nodes via gossip + indirect `ping-req`. NTT does not fire on C.
- C's local QUIC quorum count is reduced; if it falls below threshold, C self-drains (existing safety mechanism).
- CTM (on whoever is leader) sees membership_count = configured → does nothing.

**Residual edge case:** a leader with sustained partial visibility AND above local quorum, AND unable to receive corrective gossip from any peer that can reach X. NTT fires falsely; the leader's CTM would attempt to over-provision. Self-correction: the leader's local quorum eventually drops as more peers become unreachable → self-drain; or leader handoff to a node with a consistent view. The window of false provisioning is bounded by the leader's continued reachability, and the over-provisioning is itself recoverable via §7.2's `membership_count > configured` graceful drain. This residual case is accepted, not separately defended.

## 11. Scenarios — pending (to refine, not redesign)

These will be elaborated in subsequent iterations; they are expected to add details but not to change the core (per user assessment):

- **(3) Graceful node decommission** (operator drain): operator command → consensus write → node drains → leaves. NTT does not fire (this is a voluntary departure path; SWIM departure here is distinguished from failure by the *configured-size change* or *drain command* preceding it).
- **(3a) Operator scale up/down**: configured cluster size changes via consensus write. CTM reads the new size; provisions additional or initiates graceful drain accordingly. Distinct from failure-driven path.
- **(5a) Quorum loss → self-drain**: a node observing its local QUIC quorum below threshold self-drains uninterruptibly (per the existing safety contract). Sub-quorum survivors do not auto-heal.
- **(5b) Partition heal**: after a partition with a self-drained minority, the minority's containers eventually restart and re-discover via SWIM as fresh peers.
- **(6) Container restart with same NodeId**: the container restarts, SWIM re-announces, peers re-discover; if NTT had fired before the restart, the restarted container's QUIC reconnect cancels the timer (per I7); if NTT had not fired, the container simply re-joins.

## 12. Migration sketch (informational)

The migration target is the experimental branch's eventual merge to `main` as RC2 or later (not RC1; RC1 ships the current architecture with this session's narrower fixes). A staged migration:
- **E1:** Introduce NTT alongside the existing membership FSM; verify NTT emits `TopologyUnhealthy` correctly on observed departures (no action wired).
- **E2:** Wire CTM's auto-heal to NTT's events as the *primary* trigger; the existing FSM/slot pathway is left in place as a redundant trigger to compare against.
- **E3:** Confirm NTT-only is equivalent or better than FSM+slot on the chaos suite; switch the cutover.
- **E4:** Delete the FSM, the slot KV records, the gates, φ-accrual, the resurrection cell, and the supporting machinery (§8 deletion list).
- **E5:** Remove static-PEERS-direct-dial; switch QUIC to SWIM-discovery as sole input (§5).
- **E6:** Migrate code to the named-counts convention (§4 / I8).

This is a non-trivial migration — weeks of focused work, RC2-scope.

## 13. Open design questions

These are noted for the next iteration's discussion, not blockers for the foundation:

- **NTT timer value tuning** — single knob `nttDepartureTimeout`. Trade-off: transient-blip tolerance vs recovery latency. Default proposal: ~10–30s (longer than the worst observed SWIM convergence window, shorter than user perception of "stuck").
- **Container restart same NodeId vs fresh NodeId** — the current system uses fresh KSUIDs for CTM-provisioned replacements but static names for compose-managed nodes. Under the redesign, whether a restarted same-NodeId container should be treated as "re-join" or "fresh peer" is governed entirely by SWIM's discovery + QUIC's reconnect — but operator semantics (does the operator expect persistent identity?) should be confirmed.
- **DecommissionedAtomGc fate** — the redesign removes lifecycle atoms; this GC may have no remaining purpose. Confirm in scenario 3.
- **CTM auto-heal vs operator scale-up race** — if the operator scales up at the same instant a node fails, do we end up with `configured+1` containers? §7.2 handles the over-provision case via graceful drain, but the trace deserves explicit walk-through in scenario 3a.

## 14. References

- Predecessor: `aether/docs/specs/membership-architecture-spec.md` (the layered model being superseded for topology management; the simple scheme at its base — SWIM, QUIC, Rabia — is preserved).
- Discovery: `aether/docs/specs/swim-driven-topology-spec.md`.
- Current convergence (to be retired): `aether/docs/specs/slot-based-membership-convergence-spec.md`.
- Session handover with the layered-stack diagnosis: `aether/docs/internal/progress/session-handover-2026-05-28.md`.
- Memory: `[[project_cluster_b_wedge_layered_stack]]`.

---

## 15. Changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-28 | session author | Initial DRAFT — scenarios 1, 2/2a, 5 captured; pending 3, 3a, 5a, 5b, 6. |
