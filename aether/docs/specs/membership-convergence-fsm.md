# Membership-Convergence FSM — Phase-0 Specification (model only)

**Status:** Draft / exploration. **Scope of Phase 0:** model the state space on paper. No code
changes. The goal is to decide whether the NTT + CTM/LeaderReconciler machinery should become a
single explicit finite state machine, and to surface — by enumeration — the unhandled `(state, event)`
combinations that produced this session's bug cluster.

## 1. Motivation

The membership-convergence bugs found while stabilizing RC1 02-chaos are not independent defects.
They share one signature: **implicit state landing in a combination nobody designed for**, or a
`(state, event)` pair nobody handled.

- Join-grace suppressed the FAULTY *emission* but not the *state transition* the NTT streak samples.
- Terminal-eviction (`terminallyEvicted`) refused to clear for a same-id return.
- `effective = 4` while `peakMembershipCount = 5` — a member counted out while present.
- Provision → join → flap-out → re-provision churn on new-ULID replacements.

By the project's own triage rule (structural vs local), a recurring bug *cluster* in one machinery is
the tell of a missing abstraction. Two adjacent layers already prove the cure works here:

- **SWIM per-member** is an explicit state machine (`MemberState` ALIVE/SUSPECT/FAULTY + incarnation).
- **Leader election** is an explicit FSM: `Dormant → QuorumWaiting → AwaitingKvSync → Electing → Led`
  (`LeaderElectionState`, a `sealed interface FsmState<State, Event>`). When suspected this session,
  it was trivially traceable and correct.

The trouble is the **un-modeled middle layer** (NTT + LeaderReconciler/CTM), where "state" is
scattered across counters, sets, flags, timers, and maps with no single authority.

## 2. Where state lives today (the scatter)

| Holder | State | Kind |
|--------|-------|------|
| SWIM (`SwimProtocol`) | `MemberState` ALIVE/SUSPECT/FAULTY + `incarnation` | per-member FSM (good) |
| `NodeTopologyTracker` | `stableMembers` set; per-node up/down **streak** counters; `SampleBias` PRESENT/ABSENT | derived set + hysteresis counters |
| `LeaderReconciler` | `swimFaulty`, `livenessGone`, `terminallyEvicted` **sets**; `deficitSinceNanos` anchor; `armedForProvisioning`, `reachedFullMembership`, `reconcileInFlight` **flags**; `inFlightProvisioning` **map**; scheduled futures | ad-hoc flag/set/timer tangle |
| `NodeLifecycle` (`NodeState`) | STARTING → JOINING → ACTIVE → DRAINING → STOPPED | per-self FSM |
| `ClusterGenerationProjector` | core members projected from `ntt.currentMembers()` | projection |

No single place answers *"what state is member X in, and what are its legal transitions?"* So two
holders can **disagree** — the literal churn root: NTT hysteresis re-admits a node while
LeaderReconciler holds it `terminallyEvicted`; the cluster oscillates because two subsystems hold
contradictory truths about one member.

## 3. Proposal — one leader-authoritative per-member FSM

Extend the leader-election FSM discipline to membership/CTM. The **leader** runs a per-member
lifecycle FSM; followers observe. The FSM's state for each member identity is **reconstructible from
KV** (aligns with the existing "state reconstructible from KV-Store" guideline and leader handoff).
CTM provisioning becomes a **pure function of the aggregate FSM states**, not a separate flag-tangle.

The membership FSM is the **cluster-policy** layer *above* SWIM. It does not replace SWIM's
ALIVE/SUSPECT/FAULTY (transport-level detection); it **consumes** SWIM observations + transport +
quorum + lifecycle + CTM events and decides cluster membership/provisioning.

### 3.1 States (per member identity)

| State | Counts toward `effective`? | Meaning |
|-------|:--:|---------|
| `OBSERVED` | no | Seen by SWIM/transport, not yet promoted (below up-hysteresis / within join-grace). |
| `MEMBER` | **yes** | Promoted, on-duty core; counts toward configured core count. |
| `SUSPECT` | **yes** | Was `MEMBER`, transient liveness doubt; **debouncing**. Still counts — a flap must not drop the count. |
| `DEPARTING` | no | Confirmed leaving (drain in progress, or co-confirmed dead past debounce). |
| `DEAD` | no | Confirmed gone; slot vacant; eligible to drive provisioning. Terminal **for this identity**. |

Key design choice: **`SUSPECT` still counts toward `effective`.** A flapping member therefore does
not create a deficit, so it does not trigger spurious provisioning. This is the structural cure for
both the `effective=4` oscillation and the provision↔flap churn.

### 3.2 Event alphabet (grouped by source — all real today)

- **SWIM** (`SwimObservation`, each carries `incarnation`): `SwimHealthy`, `SwimSuspect`, `SwimFaulty`,
  `SwimDeparted`, `SwimUnknown`.
- **Transport (QUIC):** `PeerConnected` (fresh handshake — live proof), `PeerDisconnected`.
- **Liveness:** `LivenessGone` (3 missed ClusterSync pongs).
- **Promotion sampling (NTT hysteresis, as guards):** `UpHysteresisMet`, `DownHysteresisMet`.
- **Lifecycle:** `DrainRequested`, `Stopped`.
- **CTM:** `ProvisionDispatched`, `ProvisionJoined(newId)`, `ProvisionFailed`.
- **Cluster:** `QuorumLost`, `QuorumEstablished`, `ConfigChanged`, `LeaderAcquired`, `LeaderLost`.

### 3.3 Transition table (per member identity, leader view)

| From \ Event | SwimHealthy / PeerConnected | UpHysteresisMet | SwimSuspect / PeerDisconnected / LivenessGone | DownHysteresis + co-confirmed past debounce | DrainRequested | Stopped | join-grace expired & never healthy |
|---|---|---|---|---|---|---|---|
| **OBSERVED** | stay OBSERVED (accrue streak) | → MEMBER | stay OBSERVED | — | → DEPARTING | → DEAD | → DEAD |
| **MEMBER** | stay MEMBER | — | → SUSPECT | — | → DEPARTING | → DEAD | — |
| **SUSPECT** | → MEMBER (recover; **count preserved throughout**) | → MEMBER | stay SUSPECT (debounce) | → DEPARTING | → DEPARTING | → DEAD | — |
| **DEPARTING** | (ignore — decision pending) | — | — | — | — | → DEAD | — |
| **DEAD** | **OPEN DECISION cell (§5.1)** | — | — | — | — | — | — |

`ProvisionJoined(newId)` always introduces `newId` in `OBSERVED`; it never mutates an existing
identity's state. `ProvisionFailed`/`ProvisionDispatched` mutate only the cluster-aggregate
provisioning sub-state (§3.4), never a member's lifecycle state.

### 3.4 Cluster aggregate → CTM (pure function of member states)

```
effective      = |{ m : state(m) ∈ {MEMBER, SUSPECT} }|
deficit        = configuredCoreCount − effective
provisionAfter = deficit > 0 sustained for > deficitDebounce        // anchor, not a flag
```

- Provision exactly `deficit` new identities once the deficit has *persisted* past `deficitDebounce`
  (today's `deficitSinceNanos`, but now derived from FSM states, not a separately-mutated anchor).
- A `SUSPECT → MEMBER` recovery during the debounce window **raises `effective` → cancels the
  deficit** → no provision. (Cures the churn.)
- Surplus (`effective > configured`, e.g. a provisioned replacement plus a recovered original) →
  drain the *newest*/lowest-priority surplus identity via `DrainRequested`.

## 4. This session's bugs as `(state, event)` cells

| Bug (this session) | Today's cause | FSM cell that makes it explicit / impossible |
|---|---|---|
| **Join-grace** suppressed FAULTY emission not the streak-resetting transition | emission ≠ state; two code paths | `(OBSERVED, join-grace-expired-&-never-healthy)` is the *only* path to `DEAD` from un-promoted; while within grace the member stays `OBSERVED` and **counts/probes** — one rule, no emission/transition split. |
| **Terminal-eviction vs same-id restart** | `terminallyEvicted` set blocks recovery-clear | becomes the single explicit `(DEAD, SwimHealthy/PeerConnected)` cell (§5.1) — a *decision*, not an emergent block. |
| **`effective=4` while present** | reconciler counts only NTT stable set; a transient drop de-counts | `SUSPECT` counts toward `effective`; a present-but-flapping member is `MEMBER`/`SUSPECT`, never de-counted. |
| **provision → join → flap-out → re-provision churn** | NTT re-admit vs reconciler evict disagree; flap = remove→provision | flap is `MEMBER ⇄ SUSPECT` (damped, still counted); provisioning fires only from `DEAD`/sustained deficit. No oscillation. |
| **Under-load NODE_FAILED flaky** | killed a mid-flap replacement; no clean removal edge | a killed member goes `MEMBER/SUSPECT → DEPARTING → DEAD`; `DEAD` is the single, observable departure edge → NODE_FAILED emitted deterministically. |

That every bug maps to a single cell is the evidence that the abstraction is the right one.

## 5. Open design decisions (the spec forces these to be answered)

### 5.1 The `(DEAD, live-event-for-same-id)` cell — RESOLVED (see §9)

This was framed as a fork (support same-id return vs strict new-ULID). The prior-art survey (§9)
**resolves it: allow same-id, under level-triggered reconciliation.** Under that model the cell
*dissolves* — there is no "should we resurrect?" decision, only "observed vs desired." A returning
node is observed; if desired wants the count it is admitted as a **fresh incarnation** (old fenced,
Akka-style); if a replacement already filled the slot it is **surplus → drained**. Identity becomes
`(NodeId, incarnation)`; the prerequisites are in §9.4. (Retained here only as the pointer; the
decision and its grounding live in §9.)

### 5.2 Authority & handoff
Leader-authoritative (matches LeaderReconciler) with state **reconstructible from KV** so a new leader
rebuilds the FSM on handoff. Confirm the KV projection can reconstruct per-member state from
SWIM snapshot + lifecycle atoms.

### 5.3 Hysteresis placement
Keep up/down streaks as the **guards** on `OBSERVED→MEMBER` and `MEMBER→SUSPECT→DEPARTING`, but as
named transition guards, not free-standing counters owned elsewhere.

### 5.4 Composition
Must compose with — not duplicate — the SWIM per-member FSM (below it), the leader-election FSM
(beside it), `NodeLifecycle` (self), and `DrainProcedure`. The membership FSM consumes their
events/states; it does not re-implement them.

## 6. Invariants (model-checkable)

- **I1** A member observed SWIM-HEALTHY *now* is never in `DEAD`/`DEPARTING` (the contradiction we hit).
- **I2** `effective` is a pure function of member states; CTM provisions iff `effective < configured`
  sustained past debounce. No flag can disagree with the state-derived count.
- **I3** Exactly one authoritative state per identity (no NTT-vs-reconciler disagreement possible).
- **I4** `SUSPECT` is bounded: it must exit to `MEMBER` (recovery) or `DEPARTING` (timeout) within
  `suspectBudget`; it cannot loop indefinitely.
- **I5** Every `(state, event)` pair has a defined transition (exhaustive; enforced by the sealed
  `FsmState` switch, compiler-checked).
- **I6** A `DEAD` edge is the sole source of an observable departure event (NODE_FAILED/NODE_LEFT).
- **I7** (split-brain) No two *live* members ever share an identity. A returning `NodeId` carries a
  strictly higher incarnation; admitting it **fences (downs) the prior incarnation** (Akka rule).
- **I8** `NodeRole.PASSIVE` (worker/observer) never counts toward quorum/`effective`; ACTIVE (core)
  members do. **PASSIVE is a distinct node construction, not a transient join state** — there is no
  ACTIVE↔PASSIVE transition, by design (§9.3). A rejoining core node returns directly as ACTIVE via
  incarnation fencing (§9.4 #2); it does not pass through a PASSIVE/"learner" phase.

These become property tests / a small model-checker: enumerate states, feed event sequences, assert
I1–I6. This session's intermittent failures become *deterministic* FSM tests.

## 7. Complexity, risk, phasing

**Complexity.** The hard part is not the FSM mechanics (the `FsmState<S,E>` pattern already exists)
but **defining the state space against a distributed, eventually-consistent substrate**: SWIM health
is per-observer, QUIC is per-link, quorum is cluster-global, CTM drives real async container
lifecycles. The FSM orders the *response*, not the *inputs* — it will not stop SWIM probes from
flapping during reformation, but it makes the cluster's reaction principled (debounce in `SUSPECT`
instead of provision↔remove). Necessary-but-not-sufficient for input quality; almost certainly
sufficient for the churn *symptom*.

**Risk.** This is the most fragile subsystem (the dual-dial / isActive-grace → cluster-0 history).
A big-bang swap is unacceptable.

**Phasing (risk-bounded):**
- **Phase 0 (this doc):** model only. Zero regression risk. Already surfaces the bug cells (§4) and
  the one real decision (§5.1).
- **Phase 1 — shadow:** run the FSM in parallel with today's logic, acting on nothing, logging
  divergences across Docker runs. Divergences = bugs, surfaced deterministically.
- **Phase 2 — cutover:** replace the flag-tangle with the FSM as authority; delete `swimFaulty`,
  `livenessGone`, `terminallyEvicted`, `deficitSinceNanos`, and the boolean flags in favor of states.

**Non-goals:** replacing the SWIM per-member FSM or the leader-election FSM; making membership
globally synchronous; fixing transient SWIM probe loss during reformation (an input-quality matter).

## 8. Recommendation

Proceed with **Phase 0 → Phase 1**, building toward the **allow-same-id, level-triggered** model
adopted in §9. Phase 0 is cheap, cannot regress anything, and would itself have pre-empted this
session's whack-a-mole by forcing the §4 cells to have written answers. Decide Phase-2 cutover scope
(and RC1-vs-RC2 placement) after the shadow run reveals the true state space and divergence rate.
Foundational by the RC1/RC2 rule — argues for RC1, against rushing.

## 9. Prior art and adopted model — allow same-id, level-triggered reconciliation

A survey of leading cluster-management systems converges on one pattern, and it resolves §5.1.

### 9.1 The convergent industry pattern
- **Reconcile to a declarative desired state, level-triggered, idempotent** — the Kubernetes
  controller model. The workqueue holds *keys, not events*, deliberately forcing the reconciler to
  act on *state*, not state-*changes*. Joe Beda (k8s co-founder): *"if you are edge triggered you run
  risk of compromising your state and never being able to re-create the state; if you are level
  triggered the pattern is very forgiving."* Writing logic per event-type is an explicit anti-pattern.
- **Identity = stable logical id + monotonic incarnation/UID** — Akka `address+UID`, Serf
  `name+incarnation`, Cassandra `host_id`. Reusing a label is normal; the incarnation distinguishes
  lives.
- **A new incarnation fences the old** — Akka: a same-address rejoin with a new UID is *evidence the
  old incarnation is dead* → old auto-downed, new joins, no manual step.
- **Failed ≠ terminally dead** — Serf/SWIM does *not* purge on death; it keeps failed nodes,
  reconnects, and reaps only after a grace, distinguishing "left" (graceful) from "failed" (may
  return after a partition). **Aether's terminal-eviction is more aggressive than the SWIM-native
  behavior it is built on** — it is the outlier that fights same-id rejoin.
- **Rejoin = re-earn via a non-voting catch-up, not restored authority** — etcd adds a returning
  member as a **non-voting learner**, caught up, then promoted; stale data is wiped; membership
  changes are **serialized (one at a time)**.

### 9.2 Decision: CTM is a level-triggered reconciler; same-id is allowed
- **Desired core set is a declarative, consensus-held spec.** CTM reconciles observed → desired:
  idempotent, **environment-agnostic**, a **no-op when the environment already converged** (orchestrator
  or operator provisioned the nodes), and acts only on the gap. This is the project's stated goal of
  "zero dependency on environment behavior": auto-provisioning environments and fully-manual operators
  use the *same* mechanism. It also relaxes the `restart:"no"` constraint — a crashed node may simply
  restart and rejoin.
- **Identity = `(NodeId, incarnation)`; a new incarnation fences the old** (I7). The edge-triggered
  co-confirmation gate + `terminallyEvicted` set are replaced by this.

### 9.3 No "learner" phase — PASSIVE is a worker construction, not a join funnel (CORRECTED 2026-06-05)
The prior framing in this section (PASSIVE ≡ etcd non-voting learner; a join/rejoin enters PASSIVE then
gets promoted to ACTIVE) is **rejected as a Raft-/etcd-ism foreign to Aether.** `NodeRole.PASSIVE`
denotes a **worker/observer** node — *constructed differently* from a core node — and is filtered out of
quorum/core-membership everywhere. **There is no ACTIVE↔PASSIVE transition, by design**; the two are not
points on a promotion ladder, and there is deliberately no simple way to move between them. Rabia is
leaderless and needs no learner: a joining or rejoining **core** node comes up as ACTIVE and catches up
via the existing **single-snapshot sync**; a rejoining same-id node is admitted by **incarnation
fencing** (§9.4 #2, Docker-validated). The learner/promote machinery is therefore *not* built — §9.4 #3
is struck. (etcd's learner is retained in §9.1/§9.6 only as surveyed prior art, not an adopted model.)

### 9.4 Prerequisites (concrete, bounded)
1. **Monotonic incarnation across restarts — DONE (G1+G2, 2026-06-05).** On inspection the original
   premise was obsolete: SWIM self-incarnation does *not* stay 0 — `announceJoin` already seeds it from
   `System.currentTimeMillis()` (the "derive from boot time" option), and tombstone superseding already
   uses it to detect a genuine restart. The real defect was a **dual incarnation authority**: the metrics
   readiness epoch was fed by `BootEpoch = System.nanoTime()`, whose origin is arbitrary per-JVM and is
   **not** monotonic/comparable across restarts (a latent epoch-fencing bug) — and it disagreed with
   SWIM's `currentTimeMillis` incarnation, so `(NodeId, incarnation)` was not single-valued. **Fix:** made
   `SwimProtocol.selfIncarnation()` the single authority; the metrics collector now reads
   `max(bootIncarnation, swim.selfIncarnation())` off the *same* `bootIncarnation` that seeds the SWIM
   announce; `BootEpoch` deleted. Membership and readiness now agree on one value.
   - **HLC evaluated and rejected as the source:** the repo's `HlcClock` packed value overflows `long`
     (physical micros need 51 bits, the field is 48; `<<16` → 67 bits — a *separate* latent bug affecting
     DHT versioning + topology stamping), and an incarnation must be a stable captured-once-per-life value
     that gains nothing from a live-advancing clock.
   - **Residual (optional hardening, "G3"):** boot-millis is still vulnerable to wall-clock *regression*
     (NTP step-back / VM migration). Strict monotonicity would need a persisted counter
     (`max(persisted+1, bootMillis)`) or a regression guard; SWIM has no durable store today. Deferred —
     not required for fencing under a sane clock. (Akka UID / Serf incarnation.)
2. **New-incarnation-fences-old — DONE (2026-06-05).** Replaced the reconciler's `NodeId`-only
   `terminallyEvicted` set (which permanently blocked a co-confirmed-dead id and *contradicted* SWIM's
   already-correct incarnation-fenced tombstone) with a `Map<NodeId, Long> terminalIncarnation`. The
   SWIM-half ingress (`onSwimFaulty`/`onSwimHealthy`) now threads `incarnation`: eviction stamps the
   FAULTY incarnation; a **strictly-higher** incarnation on `onSwimHealthy` un-fences (auto-downs the
   prior life → same-id rejoin allowed), a same/lower one stays fenced (stale). The QUIC half
   (`onPeerRecovered`, no transport-plane incarnation) defers to SWIM authority via map presence. This
   aligns the reconciler with `SwimProtocol.supersedeOrRefuse`; surplus from a rejoin is drained by the
   existing `computePeersToDrain` path. Pinned by the rewritten `TerminalEviction` tests (53/53).
   **VALIDATED on Docker (2026-06-05):** a non-leader node killed → co-confirmed-dead → evicted
   (lifecycle record gone), then `docker start` under the **same NodeId**, rejoined as READY in **16s**
   (the higher boot incarnation un-fenced it; the old terminal-evict Set would have blocked it forever).
   Surplus-drain-to-5 lagged (a new-ULID replacement joined → cluster held at 6, node-5 UNKNOWN) — the
   separate **#68** post-multikill quiesce churn, not a rejoin regression.
3. **REJECTED (2026-06-05).** "Rejoin via PASSIVE" is a Raft artifact (§9.3): PASSIVE is a worker
   construction with no mode transition by design. Rejoin is fully covered by incarnation fencing
   (#2, validated) + the existing single-snapshot sync — no learner phase, nothing to build.
4. **DEFERRED (2026-06-05).** The reconciler does fire every provision/drain in one pass with no
   single-flight gate (a real concurrency), but the validation evidence for the post-multikill churn
   points at **health/quiesce** (a node going UNKNOWN — possibly the deferred consensus-stream wedge),
   **not** concurrent dispatch — so serialization might be correct hygiene yet not the churn fix. The
   etcd "one-change-at-a-time joint-consensus" framing is itself a Raft import (cf. §9.3). Deferred
   until the churn root (#68) is understood; the churn is its own investigation, not a spec-driven
   serialization change.

### 9.5 The multi-kill churn is a known field problem
Akka explicitly documents that when **multiple nodes are unreachable simultaneously**, new-incarnation
auto-downing **stalls** until the others are downed/reachable. The generation-churn we observed during
the 02-chaos multi-kill is the *same class* of problem. The field's answer is exactly §9.4:
incarnation-fenced identity + serialized changes + (later) a split-brain-resolver strategy — not a
local patch.

### 9.6 Sources
- Kubernetes level-triggered reconciliation: Chainguard "The Principle of Reconciliation"; "Level
  Triggering and Reconciliation in Kubernetes" (J. Bowes); the Kubebuilder Book "Good Practices".
- Akka Cluster `address+UID`, new-incarnation auto-down, multi-unreachable stall: Akka "Cluster
  Membership Service"; akkadotnet/akka.net #3252.
- etcd learner (non-voting → promote), remove-then-add, one-change-at-a-time: etcd "learner design".
- Cassandra `host_id` + explicit `replace_address_first_boot`: Apache Cassandra "Adding, replacing,
  moving and removing nodes".
- Serf/SWIM failed≠dead, reconnect/reap, FlapTimeout, suspect-before-dead: B. Storti "SWIM";
  hashicorp/serf config.
