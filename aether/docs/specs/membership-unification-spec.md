<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Membership Unification — Design of Record (big-bang)

**Status:** ⛔ SUPERSEDED (2026-05-30) by `aether/docs/specs/membership-architecture-v2-spec.md` — **NTT is the authoritative tracker.** This proposal built the smoothing tweak as a *parallel* `swim.MembershipTracker` type + a consensus injection + a second spec (a design explosion). The intended periodic-sample+delta smoothing is being folded **into NTT in place**; the parallel type is removed. See `aether/docs/internal/progress/membership-ntt-convergence-2026-05-30.md`. **Date:** 2026-05-29.

## 1. Motivation

The membership-v2 finale removed the old FSM/atom/slot layer cleanly. The first end-to-end Docker run of the resulting v2 path then exposed a class of bugs that all live in the *seams between overlapping notions of "who is an active node"*:

- **Cold-start consensus deadlock** (fixed, `2bf8e4e45`): no originator for the first `ClusterStateNotification.ACTIVE` after 2c.0 suppressed `TopologyObserver`'s emission.
- **Dead route-wiring**: the readiness / QUIC-evict / NTT-reconciler-toggle routes were appended to `aetherEntries` *after* the `allEntries.addAll(...)` merge → never installed. Node readiness was never reported → CDM `allocatableNodes` empty → 0 slices placed → app 404.
- **LeaderReconciler death-spiral**: wiring the above activated the NTT reconciler on a *static* cluster; a transiently-missing configured peer (node-4 stuck at QUIC `INIT`) was treated as departed → phantom `DockerComputeProvider` nodes → host OOM → cascade.

Root cause is structural, not tactical: the system computes membership in **four** places, quorum in **two**, phase in **two**, from **three** liveness signals (SWIM gossip, QUIC connectivity, pong heartbeat). Every bug lived in a seam between them.

## 2. The clean model

- **SWIM is the single liveness signal.** QUIC connect/disconnect feed *into* SWIM as hints — not a parallel reachability view.
- **One membership tracker** (SWIM-fed) is the single "who is in the cluster" source. It exposes: member set, quorum bit, phase. Both consensus (quorum) and deployment (reconcile/place) read it.
- **The ping/pong carries per-node *state* only** (`JOINING`/`READY`/`DRAINING`) — a *per-member attribute*, never a membership signal. QUIC-disconnect is the fast staleness-cleanup for that state.
- **Two speeds, on purpose:** membership is slow/debounced (reconciliation must not react to transients); per-member state is fast (allocation must not place work on a just-dropped node).
- **Derived sets:** `allocatable = members.filter(state == READY)`; `draining = members.filter(state == DRAINING)`. Membership filtered by a state lookup — **not** an intersection of two membership-shaped sets.

## 3. Module placement (grounded in the dependency DAG)

Layering (low → high): `core`/`messaging` < `consensus` < `swim` < `aether-deployment` < `aether-node`. (`swim` depends on `consensus`; `consensus` does **not** depend on `swim`.)

- **`consensus`** hosts the **`MembershipView` interface** (the liveness/membership abstraction). Already lives here. Consensus consumes the *interface*; the impl is injected from above — consensus stays decoupled from the SWIM impl.
- **`swim`** hosts the **unified `MembershipTracker`** impl (SWIM-fed; implements `consensus.MembershipView`). A SWIM-fed membership tracker genuinely belongs in the swim module. **`NodeTopologyTracker` relocates `aether-deployment → swim`** and becomes this tracker.
- **`aether-deployment`** reads the tracker via the `MembershipView` interface for reconcile/place; the per-member pong state stays a deployment/metrics concern.

## 4. The tracker mechanism (replaces NTT's leaky timers)

Current NTT: the member set updates **instantaneously** on every SWIM edge (no smoothing); a per-node one-shot timer debounces only the *departure→reconcile-trigger* path, and is bypassed by the other four reconcile triggers reading the live set. Leaky, identity-blind on the up edge, and timer-lifecycle-race-prone.

New tracker:
- **Periodic sample** (single tick) recomputes the candidate member set from the current SWIM snapshot. One deterministic tick replaces N async cancellable timers (kills the timer-race class; debounces *uniformly* — every consumer sees the same stable set).
- **Per-node hysteresis on the SET (identity-preserving):** a node enters after K consecutive up-samples (SWIM-healthy), leaves after K consecutive down-samples (SWIM-absent). Emit the set-delta **once** per stable transition.
- **Why hysteresis on the set, not a smoothed scalar count:** membership decisions are identity-based (which node; configured-core vs dynamic-worker). A smoothed integer count over {3,4,5} is degenerate (threshold oscillation) and discards identity.
- **Count-smoothing has a home one layer up** — in the `LeaderReconciler`'s scale *decision* (don't act on a shortage/surplus until stable for K samples), where magnitude matters and identity matters less. Complementary, not the membership signal.
- **QUIC connect/disconnect = SWIM hints** feeding the sampled liveness; disconnect also fast-evicts the node's per-member *state* (allocation reacts immediately even while membership is still debouncing).

Tunables (defaults, tunable later): sample-tick interval; K up/down; mapping from `nttDepartureTimeout` onto the hysteresis window.

## 5. Decisions (settled)

- **D1 = A:** unify on SWIM via the `MembershipView` abstraction; tracker impl in `swim`.
- **D2:** live membership **never mutates the consensus voting group** (deliberate via config / `SetClusterSize` / provisioning); it gates quorum + drives deployment only. Structural guard against the death-spiral class.
- **D3:** the generation **snapshot is a consensus-replicated *distribution*** of (membership + per-member state + epoch); leader-side CDM/reconciler read the tracker directly.
- **D4:** per-member state (`JOINING`/`READY`/`DRAINING`) stays a deployment concern; consensus never sees deployment readiness.
- **D5:** big-bang, committed incrementally directly on `release-1.0.0-rc1`; RC1 scope.

## 6. Component fate

| Component | Now | Target | Action |
|---|---|---|---|
| `MembershipView` (interface) | consensus | consensus | KEEP as THE abstraction |
| Unified `MembershipTracker` | — | swim | NEW; absorbs NTT |
| `NodeTopologyTracker` | deployment | swim | relocate + become the tracker |
| `LocalQuorumWatcher` | deployment | — | DELETE (quorum from tracker) |
| `ReachabilityAggregator` | deployment | — | DELETE/fold (QUIC → SWIM hints) |
| `TopologyObserver` | consensus | consensus | SHRINK to transport-registry + voting group; quorum/mode/cold-start from injected `MembershipView` |
| `ClusterPhaseView` | deployment | deployment | phase derived from tracker (thin) |
| pong + holder + fan | metrics | metrics | KEEP; per-member state map keyed by tracker membership |
| `GenerationSnapshot` pub/projector | deployment | deployment | distribution only; input = tracker |
| `LeaderReconciler` | deployment | deployment | reads tracker; + smoothed-count scale; + identity rule |
| CDM / CTM | deployment | deployment | `allocatable = members ∩ READY`; CTM unchanged |

## 7. Phased execution (each phase builds)

- **P1** — Unified `MembershipTracker` in `swim` (sample + hysteresis + emit-once; implements `MembershipView`; members + quorum + phase). Unit-tested in isolation.
- **P2** — `TopologyObserver` quorum/`inQuorum`/mode + cold-start `ClusterStateNotification` ← injected `MembershipView`; remove its QUIC-quorum eval; keep transport-registry + voting group.
- **P3** — `LeaderReconciler` + CDM + snapshot ← tracker; delete `LocalQuorumWatcher` + `ReachabilityAggregator`; `ClusterPhaseView` phase ← tracker.
- **P4** — per-member state → `Map<NodeId,State>` keyed by tracker membership; QUIC-evict cleanup; wire all routes into the live router (fixes the `aetherEntries`-after-merge bug).
- **P5** — reconciler correctness: identity-aware (never replace a configured core peer not-yet-joined) + count-smoothing for scale.
- **P6** — delete dead code, full build green, Docker validation (cold-start → slice placement → 02-chaos).

## 8. Validation bar

`./build.sh` green; consensus/swim/deployment/node unit suites green; Docker `--suites 00,02` green end-to-end (cluster forms cold, slices reach active instances, app returns 200, chaos kills recover) — the first fully-green v2 00-smoke gate.
