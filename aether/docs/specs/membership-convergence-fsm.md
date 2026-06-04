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

### 5.1 The `(DEAD, live-event-for-same-id)` cell — same-id restart

Does Aether support a node returning with the **same NodeId** (k8s StatefulSet, node reboot,
operator restart, or a false-positive recovery after a long GC/partition), or is the model strictly
`restart:"no"` + new-ULID replacements?

- **Re-admit** (support same-id restart): `(DEAD, PeerConnected)` → new `OBSERVED`. Requires a
  *live* proof to avoid resurrection-by-stale-signal. Candidate proofs, strongest first:
  fresh QUIC handshake (new connection identity, unreplayable) > local probe-ack HEALTHY > a
  **strictly higher incarnation**. ⚠ SWIM incarnation currently starts at **0** on a fresh process,
  so incarnation-gating alone would not fire on restart — a fresh-QUIC-handshake proof is the viable
  signal.
- **Strict new-ULID:** `(DEAD, *)` is a no-op; a genuine return is always a new ULID → new
  `OBSERVED`. Then the 02-chaos S20 "restart" test is unfaithful and should re-provision new ids.

This is the *only* place the architectural fork lives — one cell, not a cross-cutting concern.

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

Proceed with **Phase 0 → Phase 1**. Phase 0 is cheap, cannot regress anything, and would itself have
pre-empted this session's whack-a-mole by forcing §5.1 and the §4 cells to have written answers.
Decide Phase-2 cutover scope (and RC1-vs-RC2 placement) after the shadow run reveals the true state
space and divergence rate. Foundational by the RC1/RC2 rule — argues for RC1, against rushing.
