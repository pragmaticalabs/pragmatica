<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Membership Architecture v2 — Derive-from-Reality

**Status:** Foundation spec complete (8 scenarios settled). Implementation pending — see §13 migration plan.
**Branch:** `experimental/membership-redesign` (from `release-1.0.0-rc1` HEAD `b96619ea2`).
**Supersedes (when implemented):** the topology-management layer of `aether/docs/specs/membership-architecture-spec.md` — specifically the membership FSM, the slot-occupancy classifier, the reachability gates, the leader-pinned membership timers, and the drain coordinator's FSM integration. The **simple scheme at its base — SWIM, QUIC, Rabia, LeaderManager — is preserved unchanged**, and its reliability is the foundation this design builds on.
**Implementation target:** RC1 (foundational work per the project's RC1 vs RC2 scope rule — anything affecting architecture or foundation belongs in RC1).

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
| **`clusterMembershipCount`** (cluster SWIM membership count) | SWIM's converged member set (after gossip + `ping-req`), sourced in production via `NodeTopologyTracker.currentMemberCount()` | Cluster-consistent | Topology decisions: *is the cluster the right size against `configured`?* Drives CTM provisioning / reaping. |

Under full connectivity these counts agree. Under **partial connectivity they diverge** — and that divergence is the right signal: it says "the cluster is intact, but *this* node is locally isolated." A node whose `localQuorumCount` drops below threshold self-drains (safety); a divergence in `clusterMembershipCount` from `configured` triggers CTM provisioning or drain. **Calling both "node count" without qualification is forbidden in v2 code.**

**On `localQuorumCount` vs Rabia's voter-eligible set.** `localQuorumCount` per this definition counts QUIC-connected peers. Rabia's actually-voting set may briefly diverge (a `Paused` responder is QUIC-connected but not voting; a `Syncing` peer is QUIC-connected but catching up on the log). v2 accepts this approximation — `localQuorumCount` is the stable, decoupled signal; coupling it to RabiaEngine internals would reintroduce the parallel-state seam this redesign eliminates. If asymmetric Rabia-stall-with-QUIC-healthy is observed in chaos validation, address at the consensus layer (e.g., a Rabia-side health probe), not by changing this definition.

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
  - SWIM converged healthy notifications (`HealthyObserved` — post-gossip; used for member-set tracking and as a timer-cancellation signal symmetric with QUIC reconnect).
  - QUIC reconnect events.
- **State:**
  - **Per departed peer** — a single in-memory `ScheduledFuture<?>` (the pending one-shot timer). No event records, no claim queue, no persisted KV.
  - **Cluster member set** — an in-memory `Set<NodeId>` containing `self` (always) plus every peer for which a `HealthyObserved` has been observed since process start, minus any peer for which a `DepartedObserved` has subsequently arrived. SWIM-sourced, not persisted.
- **Output:** invokes a constructor-injected `Runnable onReconcileNeeded` callback on timer expiry. NTT carries no payload to the callback — the reconcile is fully state-derived (E2 Phase 1.5).

### 6.2 Behavior
- On SWIM `Departed(peer)` → start the NTT timer for `peer`.
- On QUIC reconnect to `peer` → cancel the timer. *Note:* SWIM `Healthy` alone does NOT cancel — only an actually reconnected QUIC channel does. This is what filters SWIM lies (stale gossip about a vanished container) from real resurrections.
- On timer expiry → remove the per-peer map entry and invoke the reconcile-trigger callback.

### 6.3 Reaction
- The leader's CTM listens to the reconcile-trigger callback and, on invocation, runs a state-derived reconcile: snapshot `clusterMembershipCount` / `configured` / `inFlightProvisioning`, derive shortage/surplus, dispatch provisioning/drain. The quorum-safety predicate is checked at the action site: *if I provision a replacement, will the resulting cluster still preserve confirmed-healthy quorum?* If yes, provision. If no (sub-quorum), do nothing — let self-drain handle dissolution.
- Non-leader nodes' callbacks are no-ops at the reconciler entry point.

### 6.4 Why this fixes the leader-handoff bug class structurally
The current `JoinDeadlineExpired` mechanism is a *one-shot event sent to the leader*. If the leader changes during the timeout window, the event is delivered to a non-leader and dropped (single-writer no-op). State is lost.

NTT replaces transient leader-targeted events with **continuous local state derived from observable inputs**. The decision data (the pending timer) lives on every node identically. The action (CTM provisioning) is leader-specific, but the moment-of-action check ("am I leader? is it quorum-safe?") happens at the action site, not at event-generation time. Leader handoff during the timeout window is harmless: the new leader has been running the same timer locally; nothing is lost.

This pattern (P4) is broadly applicable. Other leader-pinned timers (drain ack, join timeout, sync timeout, slot FILLING expiry) all collapse into this shape or disappear entirely.

### 6.5 Member set tracking (E2 Phase 1.6)

NTT maintains a SWIM-tracked `currentMembers: Set<NodeId>` populated from `HealthyObserved` (adds) / `DepartedObserved` (removes); `self` is included unconditionally. The set is the authoritative source for two consumer reads:

- **`clusterMembershipCount`** (§4) — the leader reconciler reads `ntt.currentMemberCount()` per reconcile pass instead of approximating via `QuicClusterNetwork.connectedNodeCount + 1`. SWIM is fresher than QUIC: a peer enters SWIM gossip first, then QUIC dials lag, then Rabia voter inclusion lags more. Sourcing the count from SWIM via NTT gives the freshest "who is in this cluster right now" view, which is the right input for the provisioning decision.
- **Provisioning seed-PEERS / drain selection** — `ntt.currentMembers()` returns an unmodifiable snapshot used as the seed-PEERS set when provisioning a replacement, and as the iteration domain when picking drain victims for the overprovisioned case.

A `HealthyObserved` that arrives while an NTT departure timer is still pending for the same peer cancels the timer (symmetric with QUIC reconnect — both signals mean "peer is back"). Set semantics make `HealthyObserved` and `DepartedObserved` idempotent across duplicate gossip.

## 7. CTM, simplified

CTM today is a ~1700-line slot state machine with `HEALTHY/FILLING/DEAD/EMPTY` classification, two reclaimers, the `occupantEpoch` fence, and `supersededNodeId` lineage. Under v2, CTM becomes a small reactor over derived state.

### 7.1 Inputs
- `configured` cluster size (from `ClusterConfigValue.coreCount`, KV-subscribed).
- `clusterMembershipCount` (SWIM-converged member set, cluster-consistent — §4).
- `TopologyUnhealthy` events from local NTT.
- Leader-issued `DRAIN` commands for specific nodes (heartbeat ping — §7.5.4, §8).

### 7.2 Behavior (leader only)
- **Underprovisioned** (`clusterMembershipCount < configured`): on `TopologyUnhealthy` (or directly on observing the shortfall after a configured-size increase), if quorum-safety holds → provision the difference, KSUID-named, with PEERS seeded from current cluster members.
- **Overprovisioned** (`clusterMembershipCount > configured` — e.g., a previously-departed node returns after a replacement is online, or the operator scaled down): initiate graceful drain of the excess by commanding the target via the heartbeat (§7.5.4). Selection heuristic: **newest-joined-first** by default; operator-configurable. Sequenced (not parallel) to maintain quorum throughout the shrink.
- **Drain target acknowledgement:** the target's `DRAINING` pong is the acknowledgement (§7.5.4); CTM otherwise just waits for `clusterMembershipCount` to converge.

### 7.3 What CTM stops doing
- No slot KV records with `occupantEpoch` / `supersededNodeId`.
- No `FILLING` / `DEAD` / `EMPTY` slot classification.
- No `freeStaleFillingSlots` / `freeDeadSlots` reclaimers.
- No FILLING deadline tracking.
- No `JoinDeadlineExpired` event emission (NTT replaces).
- No parallel-FSM state to reconcile.

Slots become **positions, not records**: there are `configured` positions; each is occupied iff a member is in the SWIM-converged membership set; the count of empties is `configured − clusterMembershipCount`. No per-slot state to maintain.

### 7.4 Reconciliation triggers (hybrid model)

CTM acts via a hybrid trigger model — multiple wake-up sources, all converging on a single idempotent **CAS-debounced** `reconcile()` derived from current state. Reconciliation is **fully state-derived**: triggers carry no per-peer payload — only the *fact* "something changed, re-derive intent from current `clusterMembershipCount` / `configured` / `inFlightProvisioning`".

- **`TopologyUnhealthy` events (NTT_FIRE)** from local NTT (§6) — low-latency reaction to abrupt departure. NTT's per-peer one-shot timer expires → NTT invokes a `Runnable onReconcileNeeded` callback (no event payload) → the leader-pinned reconciler debounces and reconciles.
- **`HealthyObserved` events (MEMBER_APPEARED)** from SWIM — symmetric to NTT for the surplus case. A previously-departed node becoming reachable again signals the leader may need to drain excess. This trigger is the structural replacement for the periodic tick, which used to catch surplus implicitly.
- **`QuorumLossIntent` events (QUORUM_LOSS)** from local `LocalQuorumWatcher`.
- **`configured` size changes (CONFIG_CHANGE)** observed via KV subscription — scale up/down (§12.8). Targeted-drain delivery is via the heartbeat command (§7.5.4), not a KV write. Phase 1.5 wires the entry point; Phase 2 hooks the actual subscription.
- **Leader-activation delayed reconcile (LEADER_ACTIVATION)** — on leader gain the reconciler schedules a single one-shot reconcile at `nttDepartureTimeout × 1.5`. The delay lets SWIM gossip + QUIC connections quiesce after the invasive leader-handoff event before reconciling. No immediate reconcile is emitted.

**No periodic tick.** The previous design relied on a `provisioning_timeout × 1.5` tick as a backstop. With surplus now event-signalled (MEMBER_APPEARED) and shortage signalled by NTT_FIRE, no symmetric gap remains — the tick was tracking absence of a signal, but every signal that mattered now has an event. Eliminating it removes a recurring source of redundant work and aligns with the spec principle of state-derivation over time-derivation.

**CAS-debounce.** A burst of trigger events collapses to at most two reconcile passes via the standard "in-flight + reschedule-requested" pair of `AtomicBoolean`s. The first event sets `reconcileInFlight=true` and schedules the reconcile (small ~100ms debounce); subsequent events while reconcile is in flight set `rescheduleRequested=true`; when the in-flight reconcile completes the flag is cleared and if `rescheduleRequested` was set, one follow-up reconcile is scheduled.

`reconcile()` derives its action from current `clusterMembershipCount`, `configured`, and a local-leader-only `inFlightProvisioning: Map<NodeId, Instant>` tracking peers this leader has provisioned within `nttDepartureTimeout × 1.5`. Entries past that window are evicted on every reconcile (assumed failed); the next event-driven reconcile will re-provision if the shortfall persists.

`inFlightProvisioning` is **not persisted to consensus**. On leader handoff this state is lost — new leader's first reconcile (after the activation delay) may briefly double-provision (old leader provisioned X, new leader sees X not yet in SWIM, provisions a second replacement). Wasted-provisioning is self-correcting via the overprovision-drain path (§7.2 second bullet). The simpler model is preferred over a consensus-persisted provisioning intent.

## 7.5 Node readiness & the leader↔node control heartbeat

CTM (§7) answers "how many nodes." A separate question — "which specific nodes can host deployments" — is the CDM's, and v2 answers it **without** a parallel lifecycle KV record. The old `NodeLifecycleValue.ON_DUTY` atom was a *cache* of a derived fact ("this node finished syncing and is serving"), maintained by the leader-pinned FSM, and it was the source of the phantom-retention / resurrection / stale-GC bug class. v2 deletes the cache and **derives readiness from a node-authoritative state carried on the existing metrics heartbeat.**

### 7.5.1 Node-reported state (the only authority is the node)
Each node reports exactly one state on every pong (§7.5.3). The node — and only the node — knows these locally, so it ANDs its own conditions and reports a single value:

- **`SYNCING`** — QUIC-connected and gossiping, but local consensus is not yet `Active` (still applying the snapshot, or re-syncing after a `ConsensusPassive` edge). Not allocatable.
- **`READY`** — local `ConsensusActive` **and** local subsystems up. Allocatable for deployments.
- **`DRAINING`** — the node has entered the §8 drain procedure (by leader command or local quorum-loss). Not allocatable; CDM migrates work off it.

Transitions are driven by **local** signals: `ConsensusActive`/`ConsensusPassive` (RabiaEngine), the subsystem-ready signal, the inbound drain command (§7.5.4), and the `LocalQuorumWatcher` self-drain trigger. A `ConsensusPassive` edge moves `READY → SYNCING` (the node is no longer operational and must re-sync) — this is how partition-recovery is reflected without any cluster-side state.

### 7.5.2 The leader's readiness view (derived, never persisted)
The leader maintains an **in-memory** map `NodeId → (state, incarnation, lastSeenPong, syncCountdown)`, populated purely from inbound pongs and keyed by `(NodeId, incarnation)`. This map is **the** answer to "which peers are deployment-ready." Properties:

- **Not in consensus/KV.** It is derived/ephemeral; persisting it would re-introduce the stale-GC problem. Durable *intent* (deployments, `coreCount`) stays in KV; derived readiness does not (I13).
- **Self-cleaning.** A node entry is evicted on **either** a QUIC-disconnect (§7.5.5) **or** missed pongs (≥ `pingTimeoutThreshold`, default 3 ≈ 3s) — whichever fires first. Crash ⇒ transport drops ⇒ entry vanishes; no tombstone to reap.
- **Leader-agnostic / handoff-trivial.** A newly-elected leader rebuilds the entire map from the next round of pongs (≈ one ping interval). No state transfer, no KV read. The `leaderActivationDelay` (§14) is the warmup window — the leader does not act on the map (reap, drain, migrate) until warmed up, so it never reaps a node whose first pong it simply hasn't heard yet.

### 7.5.3 Wire format (reuses existing messages)
The leader↔node metrics heartbeat already carries everything but two fields:
- **Pong (node→leader):** the existing `lifecycleState` field is repurposed to carry `SYNCING|READY|DRAINING`; the node's **incarnation** (SWIM incarnation — already advances on restart) is added so the leader rejects a stale prior-incarnation pong and never misattributes a fast-restart `DRAINING→SYNCING` flip to one continuous life. The existing `readyCandidate` field and its `NodeReadinessTracker → ForceOnDuty` path are deleted (§10).
- **Ping (leader→node):** the existing per-peer ping (`sendOnePing(peer)`) gains an optional per-target **command** (`DRAIN`). The existing `rabiaTerm`/`epochTerm` fencing (a leader-change counter) is reused so a deposed leader's commands and stale pongs are rejected (I14).

Ping interval is 1s (default), so readiness detection and drain-initiation latency are ≈ one interval; black-hole detection is ≈ `pingTimeoutThreshold × interval` (~3s) — replacing what φ-accrual used to provide.

### 7.5.4 Drain as command/ack RPC (replaces `DrainRequestKey`)
Operator-initiated and CTM-initiated (overprovision/scale-down) drains are delivered as the `DRAIN` command on the ping, **not** as a KV record:
1. Leader adds target `X` to its (in-memory, leader-local) drain set; the next ping to `X` carries `DRAIN`.
2. `X` enters the §8 procedure, reports `DRAINING` on its pong.
3. Leader sees `DRAINING` → CDM stops placing on `X` and migrates its slices.
4. `X` finishes draining → `halt(2)` → QUIC drop → evicted; SWIM converges to `Departed`; CTM reacts to the count change (§7.2).

**Durability tradeoff (accepted, I14):** the command is best-effort. If the leader changes *after* an operator drain is requested but *before* the ping delivers it, the command is lost — mitigated by (a) operator API returning success only once a `DRAINING` pong is observed, so the CLI retries against the new leader; and (b) CTM scale-down being self-healing (the new leader re-derives overprovision and re-commands). An **in-progress** drain survives leader change trivially — `X` keeps reporting `DRAINING`, and the new leader migrates on observing it, no re-command needed. The **quorum-loss self-drain** path (§8.1, §12.5) needs no command at all — `X` initiates locally (it cannot reach a leader anyway) and the majority observes a plain departure.

### 7.5.5 Failure detection & QUIC connectivity as a routed event
Failure detection is two layers, no φ-accrual:
- **SWIM** — leaderless, cluster-wide membership truth (the backstop; drives NTT/CTM per §6/§7).
- **Missed-pong** — fast leader-side liveness for the control decisions the leader is already pinned to make (subsumes φ-accrual's black-hole role with an integer count instead of a suspicion level).

QUIC connect/disconnect is promoted to a first-class **`Message.Local`** routed event (carrying the epoch) so its several consumers — NTT, `LocalQuorumWatcher`, the reachability aggregator, and the leader's readiness-view evictor — subscribe uniformly rather than via ad-hoc callback taps. Eviction is **epoch-matched**: a late `Disconnected(X, epoch=n)` must not evict a fresh `(X, epoch=n+1)` entry.

### 7.5.6 Stuck-SYNCING reaper
A node that is a SWIM member but never reaches `READY` counts toward `clusterMembershipCount` (so CTM thinks the cluster is full) yet cannot host work — a capacity hole. The leader reaps it: a countdown per `(NodeId, incarnation)` initialized on the first `SYNCING` pong, decremented on each subsequent `SYNCING`, **reset on `READY`**, and — distinct from the missed-pong counter (`SYNCING` = "still trying"; silence = "black-hole") — at zero it **terminates** the stuck node (nothing to gracefully drain; it never synced). Termination drops `clusterMembershipCount`, and the normal underprovisioned path (§7.2) provisions a fresh replacement. Leader handoff resets the countdown (lenient — a fresh leader grants a new window).

## 8. Drain — the unified self-shutdown procedure

Drain is a node-local procedure with a small set of triggers. The membership layer does not have a drain state machine; the membership-layer effect of any drain is identical to abrupt departure (observed silence).

### 8.1 Triggers (all paths converge here)
- **Operator scale-down** (Case A — configured size decreases): the leader selects shrink targets and commands each (§7.5.4) → trigger drain.
- **Operator / CTM specific drain** (Case B — leader sends the `DRAIN` command on the heartbeat ping, §7.5.4): node receives the command → trigger drain.
- **Quorum-loss self-drain (safety):** node observes its `localQuorumCount` is below threshold for ≥ `quorumLossDrainThreshold` (§14) → trigger drain.
- **Partial isolation:** is the same as quorum-loss self-drain (it's *how* a partially-isolated node observes its local quorum failing); no separate trigger.
- **Application overload / planned restart** (out of scope for this spec, but the unified path supports it).

### 8.2 The procedure (one procedure, regardless of trigger)
1. **Stop accepting new work** (application layer). Existing in-flight work proceeds.
2. **Drain in-flight** (application layer — slice migration, request completion, etc.). Outside this spec's scope.
3. **Stop SWIM probes and emit one LEAVE message.** Once application drain is complete. *Order matters:* peers must continue routing during the drain window; only when application work is done should the node go silent. The LEAVE message is a SWIM-internal hint for peers to skip suspect-aging (transitioning the departing peer to FAULTY/Departed immediately rather than after `suspectTimeout × 3`); functional correctness does not depend on LEAVE delivery — silence-driven detection is the backstop.
4. **Exit with `Runtime.halt(2)`.** Distinguishes self-drained exits from clean stop (0), SIGKILL (137), SIGTERM (143) for operator/test observability.

From the cluster's perspective, step 3 *is* the departure signal — SWIM detects silence, converges on `Departed`, NTT fires, CTM reacts per §7.2. **There is no separate "voluntary LEAVE" SWIM message in v2.** Silence is the universal departure signal.

### 8.3 Uninterruptibility (when applicable)
- **Quorum-loss-triggered drain is uninterruptible** (I9). Once started, it does not abort even if quorum returns mid-drain. Reasons: prevents oscillation (lose quorum → start drain → quorum returns → cancel → repeat) and prevents serving stale data after re-joining without re-sync.
- **Operator-initiated drain may be cancellable** (operator decision). If cancelled mid-drain: node resumes SWIM probes; if SWIM had marked the node `SUSPECT`/started gossip, the resumed probes flip it to `ALIVE`; if NTT timer had started on peers, QUIC reconnect cancels it (per I7). Clean recovery.

### 8.4 Operator visibility
A drain-progress field MAY be written to a dedicated KV key by the draining node (e.g., `DrainProgressKey(nodeId)`) for operator polling. This is optional, application-layer concern — *not* membership state. Membership convergence is observable via the standard endpoints (`/api/cluster/topology`, `/api/cluster/generation`).

**Caveat:** drain-progress publishing requires consensus, so it is available for operator-initiated drains (§8.1 first two triggers) but **not** for quorum-loss-triggered drain (§8.1 third trigger) — by definition, consensus is unavailable in that case. The universal observability signal for any drain is the `Runtime.halt(2)` exit code (§8.2 step 4); peers observe departure via SWIM regardless of KV publishing.

### 8.5 Drain command delivery (no KV record)
The mechanism by which operators (and CTM scale-down) target a specific node for drain is the `DRAIN` **command on the leader→node heartbeat ping** (§7.5.4) — **not** a KV record. There is no `DrainRequestKey`. Rationale: a drain target keyed by `NodeId` in KV has the same stale-GC problem as the deleted `NodeLifecycleValue` (who deletes it when the node departs? what if a same-NodeId restart re-reads a stale request?). The heartbeat command sidesteps all of it — the command is delivered only while the channel is live, the node's `DRAINING` self-report is the durable-enough acknowledgement, and there is nothing to GC.

The tradeoff (best-effort delivery; in-progress drains survive leader change; operator-retry / CTM-re-derive cover the initiation-window leader change) is detailed in §7.5.4. Operator visibility is unchanged (§8.4): convergence is observable via the standard endpoints, and the `halt(2)` exit code is the universal signal. A drain audit event SHOULD be emitted when the leader issues the command (the command itself is off the consensus log, so audit must be explicit).

## 9. Design rules

- **R1: Atomic operator commands.** Operator commands that combine multiple consensus writes (e.g., drain + configured-size decrease) MUST commit atomically — single multi-put through consensus, or none at all. Sequential writes admit small race windows where CTM's reaction is non-deterministic.
- **R2: Quorum-safety on configured-size change.** A `coreCount` reduction that would put the cluster below quorum (or below an operator-set minimum) is **rejected at the operator API / consensus-write validation** — action-site validation, not a parallel state machine. Same principle as CTM's "don't provision below quorum" guard.
- **R3: Drain sequencing on scale-down.** When CTM drains multiple nodes for a scale-down, drains MUST be sequenced (not parallel), each waiting for `clusterMembershipCount` convergence before initiating the next, to maintain quorum throughout the transition.

## 10. What this replaces — explicit deletion list

For implementation traceability, the following are removed (entire modules / classes / mechanisms):

| Removed | Reason | Status |
|---|---|---|
| `MembershipFsm` + lifecycle states `Untracked` / `Provisioning` / `Joining` / `OnDuty` / `Draining` / `Stopped` | State derived from SWIM + QUIC, not maintained | PENDING (Phase 2c) |
| `ClusterMembershipReducer` | Pure-function reducer over the deleted lifecycle | PENDING (Phase 2c) |
| `ReachabilityGate.isConfirmedUnreachable` (2-plane gate) | SWIM convergence does this natively | PENDING |
| φ-accrual detector (`PhiAccrualDetector`, `PhiAccrualConfig`, `PhiObserver`, `PhiWarmth`) + DivergenceLogger + `FsmDecisionEvent`/`Type` + `NttObservationFlag` | SWIM is the detector; observation-only ramp completed | **DELETED (Phase 2a, 2026-05-28)** |
| `JoinDeadlineExpired` event + leader-pinned timer | NTT replaces | PENDING (Phase 2c) |
| Slot KV records (`ProvisioningSlotValue` with `occupantEpoch`/`supersededNodeId`) | Slots become count-derived positions | PENDING (Phase 2c) |
| `freeStaleFillingSlots` / `freeDeadSlots` / FILLING expiry | No FILLING state to reclaim | PENDING (Phase 2c) |
| `applyExternalLifecycleRemove` → `applyLifecycleRemoveWithSlot` re-bind chain | No parallel lifecycle to remove | PENDING (Phase 2c) |
| `(Untracked, SwimHealthy) → untrackedDirectToOnDuty` reducer cell (the resurrection vector) | No parallel lifecycle to write | PENDING (Phase 2c) |
| Static-PEERS pre-population of `topologyManager` for QUIC dialing | SWIM is the sole input (§5) | PENDING |
| `Draining` lifecycle state + `DrainCoordinator` ↔ membership-FSM integration + `awaitDrainAck` as an FSM transition | Drain is node-local + KV-observable (§8) | PENDING (Phase 2b/c) — `ConsensusDrainCoordinator` **DELETED (Phase 2b, 2026-05-28)**; FSM-routed `DrainCoordinator` now a no-op stub, Phase 2c removes the interface |
| `SelfDrainCoordinator` as an FSM-integrated component | Replaced by a small local quorum observer that triggers §8 | **DELETED (Phase 2b, 2026-05-28)** — execution moved to `DrainProcedure` (membership.ntt); `LocalQuorumWatcher` is the trigger source |
| `OrphanSelfDrainChecker` (slot-binding orphan predicate, periodic 5s tick) | NTT drives the sole departure-detection path; orphan-slot predicate moot under v2 | **DELETED (Phase 2b, 2026-05-28)** |
| `DecommissionedAtomGc` | No lifecycle atoms to GC (subject to confirmation during implementation) | PENDING |
| SWIM voluntary `LEAVE` message **as a membership-layer state transition** (the FSM's Draining→Stopped path with `awaitDrainAck`) | Membership layer is cause-agnostic (P2). LEAVE is **preserved as a SWIM-internal acceleration of `DepartedObserved`** (peer skips suspect-aging on authenticated LEAVE receipt); see §8.2 step 3 | PENDING |

| `NodeLifecycleKey` / `NodeLifecycleValue` + `ON_DUTY`/`JOINING`/etc. states + serializer cases | No lifecycle cache — readiness is node-reported on the heartbeat (§7.5), membership is SWIM-derived (`MembershipView`) | PENDING (Phase 2c) |
| `NodeReadinessTracker` + `ClusterSyncPong.readyCandidate` + `ClusterSyncPongSignalFan.ReadyCandidateSink` + `emitForceOnDuty` + `clearReadinessOnSelfOnDuty` | Replaced by the node-reported `SYNCING/READY/DRAINING` state on the pong (§7.5.3) | PENDING (Phase 2c) |
| `DrainRequestKey` / `DrainRequestValue` (never implemented) | Drain delivered as a heartbeat command (§7.5.4); no KV record | N/A — not built |

What remains: SWIM, QUIC, Rabia, `LeaderManager` (all unchanged), simplified CTM (§7), NTT (§6), per-node `LocalQuorumWatcher` (drives §8's quorum-loss trigger), and the leader↔node control heartbeat carrying node-reported readiness state + `DRAIN` commands (§7.5). **No membership/readiness/drain KV records.**

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
- **I12:** NTT holds a per-peer `Map<NodeId, ScheduledFuture<?>>` of pending one-shot departure timers — no event records, no claim queue. On timer expiry NTT invokes a `Runnable onReconcileNeeded` callback (no payload); the leader-pinned reconciler is fully state-derived. Local state only — not persisted to consensus.
- **I13:** Node readiness (`SYNCING/READY/DRAINING`) is **node-authoritative and transport-carried** (heartbeat pong), held by the leader only in memory and rebuilt from pongs on handoff. It is **never** persisted to KV — derived/ephemeral state has no consensus record (only durable *intent* — deployments, `coreCount` — lives in KV). The leader's readiness view is self-cleaning via QUIC-disconnect / missed-pong eviction; there is no stale-record GC.
- **I14:** The leader↔node control channel is epoch-fenced both ways: the leader rejects stale prior-incarnation pongs (node SWIM incarnation), and nodes reject commands from a deposed leader (leader-term). `DRAIN` command delivery is best-effort (lost only if the leader changes mid-initiation → operator-retry / CTM-re-derive); an **in-progress** drain survives leader change via the node's continued `DRAINING` self-report.

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

- **Case A — scale-down:** operator writes `coreCount = N-1` (R2 quorum-safety pre-checked). The leader's CTM observes overprovision → commands the selected excess node to drain via the heartbeat (§7.5.4). Node X drains → exits. SWIM converges on departure; CTM sees `clusterMembershipCount = configured` → no-op.
- **Case B — specific replacement (size unchanged):** operator issues a drain for X via the management API → leader sends the `DRAIN` command (§7.5.4). Node X drains → exits. CTM sees `clusterMembershipCount < configured` → provisions a fresh KSUID replacement (§7.2 underprovisioned path).

The membership layer is unaware of the distinction; the configured-size at the moment of CTM's reaction determines whether a replacement is provisioned. The `Draining` lifecycle state and `awaitDrainAck` FSM transition are deleted (§10).

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
- **Scale down:** operator writes `coreCount = N - k` (R2 quorum-safety pre-checked). CTM observes overprovision → commands `k` selected nodes (default newest-joined-first; operator-overridable) to drain via the heartbeat (§7.5.4), R3 sequenced. Each drained node follows §12.4 Case A. CTM observes convergence.

## 13. Migration plan

Migration is staged so the existing system continues to work throughout. The eventual cutover is one step, but verification at each stage is per-scenario.

- **E1.** Introduce `NTT` and `LocalQuorumWatcher` alongside the existing membership FSM. Wire `NTT` to SWIM converged departure; verify `TopologyUnhealthy` emission against observed departures. **No action wired** — observation only.
- **E2.** Wire CTM's auto-heal to `TopologyUnhealthy` as the *primary* trigger; leave the existing FSM/slot pathway as a redundant trigger for comparison. Add the `clusterMembershipCount` derived from SWIM-converged set; add `localQuorumCount` as a renamed metric. Migrate code to use named counts (I8).
- **E3.** Run the chaos suite with NTT as primary; confirm equivalence-or-better against FSM+slot path. Iterate `nttDepartureTimeout` and `quorumLossDrainThreshold` defaults.
- **E4.** Cut over to NTT-only. Delete the membership FSM, the slot KV records, the gates, φ-accrual, the resurrection cell, the supporting machinery (§10 deletion list).
- **E5.** Remove static-PEERS pre-population of `topologyManager` for QUIC dialing; switch QUIC to SWIM-discovery as sole input (§5 + I6).
- **E6.** Replace `DrainCoordinator`'s FSM-integrated drain with the §8 unified procedure, triggered by the §7.5.4 heartbeat `DRAIN` command (operator/CTM) or the local quorum-loss path. Delete `Draining` lifecycle state, `awaitDrainAck` as an FSM transition, `SelfDrainCoordinator` as an FSM-integrated component. No `DrainRequestKey` (§7.5.4, §8.5).

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
- **`membership.nttObservation` feature flag.** Controls NTT instrumentation during the E1-E4 migration ramp. Values: `off` (NTT inert, no observation — production default during initial E1 ramp); `universal` (full NTT observation active on every node, leader-only reaction). The flag exists so observation-only NTT code can land in rc1 without behavior change, then be ramped to `universal` once divergence-logger telemetry confirms NTT matches the existing FSM path. Removed entirely at post-cutover cleanup.
- **`leaderActivationDelay`.** Derived as `nttDepartureTimeout × 1.5` (default: 22.5s). On leader gain the reconciler schedules a single one-shot reconcile after this delay so SWIM gossip and QUIC connections quiesce before the first reconcile pass runs. The previous design ran an immediate reconcile on leader-activation plus a periodic `provisioning_timeout × 1.5` tick; both are eliminated in E2 Phase 1.5 because reconciliation is now fully state-derived (every signal that mattered has an event).

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
| 2026-05-28 | session author | RC1-scope correction + implementation-consultation decisions: §1 retargeted to RC1; §4 `localQuorumCount` vs Rabia voter-set divergence note; §7.4 NEW (hybrid reconciliation triggers — NTT events + KV-subscribed configured/drain changes + leader-activation map-drain + periodic tick at `provisioning_timeout × 1.5` with local-only `inFlightProvisioning`); §8.2 step 3 amended (LEAVE preserved as SWIM acceleration); §10 LEAVE entry amended (delete only the FSM state-transition machinery, keep SWIM-internal LEAVE); I12 NEW (NTT per-peer claimable map, claim-then-process); §14 entries added for `membership.nttObservation` flag and reconciliation-tick period rationale. |
| 2026-05-28 | session author | E2 Phase 1.5 simplification — NTT collapsed to per-peer one-shot timer map (no event records / claim queue / drain); reconciliation fully state-derived via CAS-debounced `triggerReconcile(trigger)`; periodic tick removed (surplus now event-signalled via SWIM `HealthyObserved`); leader-activation reconcile delayed by `nttDepartureTimeout × 1.5`. `ReconcileTrigger` updated (`NTT_DRAIN`→`NTT_FIRE`; `PERIODIC_TICK` removed; `MEMBER_APPEARED`, `CONFIG_CHANGE` added). `ReconcileIntent.peersToProvision`/`peersToDrain` replaced with `provisionCount`/`drainCount` (reconciler owns peer selection internally). §6/§7.4/§14/I12 rewritten. |
| 2026-05-28 | session author | E2 Phase 1.6 — NTT becomes the authoritative cluster-membership source. NTT subscribes to both `DepartedObserved` and `HealthyObserved`; maintains `currentMembers: Set<NodeId>` (self always included). `LeaderReconciler` drops the QUIC-based `clusterMembershipCountSupplier` / `currentClusterMembersSupplier` constructor params and reads both `ntt.currentMemberCount()` and `ntt.currentMembers()` directly — SWIM is fresher than QUIC for the "who is in the cluster right now" view. `HealthyObserved` arriving while an NTT timer is pending cancels the timer (symmetric with QUIC reconnect). New §6.5 (member set tracking); §4 amended to note the production source; §6.1 inputs amended to include `HealthyObserved`. |
| 2026-05-28 | session author | E2 Phase 2b — drain coordinators deleted. `SelfDrainCoordinator`, `ConsensusDrainCoordinator`, `OrphanSelfDrainChecker` (+ their `SelfDrainConfig` / `SelfDrainEventPublisher` helpers and unit tests) removed. Execution surface extracted to new `DrainProcedure` (membership.ntt package) — a §8.2 unified procedure: tracker-gate-close → `onAllDrained`-or-grace → SWIM `LEAVE` (Phase 6 wiring; no-op runnable for now) → `jvmExit`. Triggers separated from execution: `LocalQuorumWatcher` quorum-loss listener now drives `DrainProcedure.initiate(QUORUM_LOSS)` directly; the `QuorumStateNotification.DISAPPEARED` MessageRouter route was rewired to the same procedure. `DrainReason` enum gained `QUORUM_LOSS` variant. `AetherNode` drops the 1Hz `onConnectivityChange` tick and the 5s `OrphanSelfDrainChecker::check` tick. FSM-routed `DrainCoordinator` interface remains structurally (used by `MembershipFsm.InvokeDrain` and `CTM.drainNode`) but is now backed by `NoOpDrainCoordinator` — Phase 2c removes the interface entirely when the FSM goes. §10 deletion list updated. |
| 2026-05-28 | session author | E2 Phase 2a — peripheral deletions executed. Removed: φ-accrual stack (`PhiAccrualDetector`/`PhiAccrualConfig`/`PhiObserver`/`PhiWarmth` + tests + chaos spike), divergence-logger (`DivergenceLogger`/`FsmDecisionEvent`/`FsmDecisionType` + test), `NttObservationFlag` migration-ramp gate + `nttObservation` field on `MembershipConfig`/`MembershipConfigBinding` + Main lift helper. NTT/LocalQuorumWatcher/LeaderReconciler now wire unconditionally on every node. `ClusterMembershipReducer.apply(state, event)` drops the `PhiWarmth` parameter; `(ON_DUTY, SwimFaulty)` cell now decommissions unconditionally (SWIM trusted directly). `MembershipFsm` drops the `phiWarmth` field + `addDecisionListener` + decisionListeners machinery. `AetherNode.buildMembershipFsm` signature drops the `PhiWarmth` arg; `attachQuicConnectivityReporter` takes raw `Consumer<NodeId>` taps instead of `Option<NttQuicTaps>`; `NttQuicTaps` record + `emitForceDecommission` helper deleted. Spec §10 entries marked DELETED. |
| 2026-05-29 | session author | **NEW §7.5 — node readiness & the leader↔node control heartbeat.** Readiness derived from a node-authoritative `SYNCING/READY/DRAINING` state carried on the existing metrics pong (repurposing `lifecycleState`, adding SWIM-incarnation epoch); leader keeps an in-memory `(NodeId,incarnation)→state` view (never KV, self-cleaning via QUIC-disconnect / missed-pong eviction, rebuilt from pongs on handoff). Drain delivered as a `DRAIN` **command on the ping** (per-peer), best-effort with operator-retry / CTM-re-derive; in-progress drains survive handoff. φ-accrual stays deleted (missed-pong subsumes black-hole detection). Stuck-`SYNCING` reaper (countdown → terminate → auto-heal). QUIC connect/disconnect promoted to `Message.Local` routed events (epoch-matched eviction). CDM allocatable-gate rewired from KV `ON_DUTY` to the leader readiness view. **`DrainRequestKey` removed from the design entirely** (§8.5 rewritten, §7.1/§7.2/§7.4/§12.4/§12.8/§13-E6 aligned, §10 deletion list adds `NodeLifecycleKey`/`NodeReadinessTracker`/`readyCandidate`/`ForceOnDuty` chain). New I13 (readiness node-authoritative, never KV) + I14 (epoch-fenced control channel, best-effort drain command). |
