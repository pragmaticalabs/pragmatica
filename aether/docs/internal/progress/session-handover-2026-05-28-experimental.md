<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Session Handover — 2026-05-28 (experimental) — Membership v2 redesign spec finalized

**Branch:** `experimental/membership-redesign` (off `release-1.0.0-rc1` HEAD `b96619ea2`). **Local, unpushed.**
**Companion handover for the same date** (rc1-track work): `session-handover-2026-05-28.md` — the layered-stack diagnosis, the SWIM-ungate (`309a4cb1d`), the slot-flap-reclaim fix (`14317ac8b`+`695e3139f`+`295625c36`), and the conclusion that a deeper SWIM-resurrection bug now dominates the cluster-B chaos wedge. This experimental track is the **structural answer** to that diagnosis — a redesign that eliminates the bug class rather than patching the next layer.

## 0. TL;DR
The cluster-B chaos wedge is the tip of a layered-stack bug class: parallel topology state maintained on top of SWIM+QUIC, with gates to reconcile them, where every interaction seam is a race. After diagnosing this on the rc1 branch, the user proposed total simplification rather than chasing the next layer. This session designed the replacement, scenario by scenario, and wrote the foundation spec.

**The result:** `aether/docs/specs/membership-architecture-v2-spec.md` — a complete foundation spec covering all 8 practical scenarios (formation, abrupt departure + auto-heal, asymmetric partial visibility, graceful decommission, quorum loss → self-drain, partition heal, container restart same NodeId, operator scale up/down). RC2-scope implementation; next session can start at migration plan E1 (see spec §13).

The redesign is **subtractive**: the simple scheme (SWIM → QUIC → Rabia → LeaderManager) is preserved unchanged; the parallel topology-management layer (membership FSM, slot-occupancy classifier, reachability gates, leader-pinned timers, drain-FSM integration) is **deleted**. A small new component (NTT — Node Topology Tracker) replaces the leader-pinned timer; a simplified CTM replaces the slot machinery.

## 1. The four design principles (the intellectual core)

These are the spec's load-bearing principles. Every scenario reduces to them:

- **P1 — Derive, don't duplicate.** The simple scheme (SWIM→QUIC→Rabia→LeaderManager) is authoritative. Any topology-change machinery derives from it; no parallel state requiring gates to keep it reconciled.
- **P2 — Departure is observed silence, cause-agnostic at the membership layer.** Crash, kill, drain, network failure — all produce the same SWIM-observable effect (silence). The membership layer reacts to silence; the cause lives upstream and downstream.
- **P3 — Drain is the unified self-shutdown procedure, trigger-agnostic.** Operator command, quorum loss, partial isolation — all converge on the same drain → stop-SWIM → exit-halt(2) path. One procedure, multiple triggers.
- **P4 — Local derivation + leader-specific action.** Decision data (timers, observations) is derived continuously on every node from observable inputs. Only the *action* is leader-specific. Leader-handoff is structurally safe by construction — nothing transient is in flight to lose.

## 2. Key design pieces

- **NTT (Node Topology Tracker)** — per-node component listening to SWIM converged departure (`FAULTY`/`Departed`, not local `SUSPECT`). On departure, starts a local timer; on QUIC reconnect, cancels it; on timer expiry, emits local `TopologyUnhealthy(peerId)` notification. Only the leader's CTM acts. Replaces the leader-pinned `JoinDeadlineExpired` timer and the entire bug class around it (this session's flap-fix lived here).
- **Two named counts** — `localQuorumCount` (QUIC, local-per-node, consensus liveness) and `clusterMembershipCount` (SWIM, cluster-consistent, topology decisions). Naming them distinctly resolves the "node count" conflation that's half of the current confusion.
- **PEERS via SWIM only** — PEERS becomes SWIM's seed list; QUIC dials only from SWIM-discovered peers. Eliminates the cold-boot vs auto-heal asymmetry that's been a recurring trip-hazard.
- **Drain = stop SWIM probes** — once a node is done draining its application, it just stops responding to SWIM. Peers detect via normal failure-detection. No "voluntary LEAVE" message, no Draining FSM state, no awaitDrainAck.
- **`DrainRequestKey(nodeId) → { requestedAtHlc, requestedBy? }`** — single KV record by which operators target a specific node for drain. HLC-timestamped (causal ordering interleaves correctly with R1-atomic operator commands).

## 3. What this deletes (spec §10 explicit list)

For implementation traceability: `MembershipFsm` + 6 lifecycle states, `ClusterMembershipReducer`, `ReachabilityGate.isConfirmedUnreachable`, φ-accrual detector, `JoinDeadlineExpired` timer, slot KV records with `occupantEpoch`/`supersededNodeId`, `freeStaleFillingSlots`/`freeDeadSlots`/FILLING-expiry, the `(Untracked, SwimHealthy) → OnDuty` resurrection cell, static-PEERS pre-population of `topologyManager` for QUIC, `Draining` lifecycle state + `DrainCoordinator`↔FSM integration + `awaitDrainAck`, `SelfDrainCoordinator` as FSM-integrated, SWIM voluntary `LEAVE` message handling, possibly `DecommissionedAtomGc`. Roughly ~1700 lines of CTM slot machinery + the entire FSM module + the gates.

## 4. The 8 settled scenarios

| # | Scenario | Resolution |
|---|---|---|
| 12.1 | Initial formation (cold/warm boot) | Unchanged simple scheme + PEERS-via-SWIM |
| 12.2 | Abrupt departure + auto-heal | NTT pattern |
| 12.3 | Asymmetric partial visibility | SWIM convergence + two-counts; partial-isolated node self-drains |
| 12.4 | Graceful decommission (Case A scale-down, Case B specific replacement) | Configured-size change determines outcome; one drain procedure |
| 12.5 | Quorum loss → self-drain | `LocalQuorumWatcher` triggers unified drain procedure; uninterruptible |
| 12.6 | Partition heal | Composes from §12.7 + overprovision drain |
| 12.7 | Container restart same NodeId | QUIC reconnect cancels NTT timer; over-provision drain if late |
| 12.8 | Operator scale up/down | Configured-size change drives CTM provisioning or sequenced drain |

## 5. Trust model (the deliberate trade)

v2 trusts SWIM (for converged discovery + failure detection) and QUIC (for local transport reality). This is the opposite trade-off from the current architecture: the current gates partially defend against SWIM bugs at the cost of the entire bug class this redesign eliminates. v2's bet rests on (a) the empirical record (SWIM has been the most stable component) and (b) the principled observation (SWIM is *designed* to be the failure detector; using it as one is using it correctly). If SWIM ever requires hardening, that work lands in SWIM directly — not as a parallel gate elsewhere. Spec §2.1.

## 6. Migration plan (spec §13) — what next session can pick up

E1-E6 staged so the existing system continues to work throughout:
- **E1.** Introduce `NTT` + `LocalQuorumWatcher` alongside the existing FSM. Observation only — no action wired.
- **E2.** Wire CTM's auto-heal to `TopologyUnhealthy` as the *primary* trigger; existing FSM/slot pathway redundant. Migrate code to named counts (`localQuorumCount`, `clusterMembershipCount`).
- **E3.** Validate on the chaos suite; iterate `nttDepartureTimeout` and `quorumLossDrainThreshold` defaults.
- **E4.** Cut over to NTT-only. Delete the FSM, slot KV records, gates, φ-accrual, resurrection cell.
- **E5.** Remove static-PEERS pre-population of `topologyManager` for QUIC dialing; SWIM is sole input.
- **E6.** Replace `DrainCoordinator`'s FSM-integrated drain with §8 unified procedure + `DrainRequestKey`. Delete `Draining` lifecycle state + `awaitDrainAck` + `SelfDrainCoordinator`.

Each stage independently verifiable. Multi-week effort. The migration shrinks the codebase substantially.

## 7. Open questions / tunables (spec §14)

- `nttDepartureTimeout` — proposed default 15s.
- `quorumLossDrainThreshold` — proposed default 8s (preserves current S19).
- Scale-down drain selection heuristic — default newest-joined-first.
- Replacement-flap S01 budget alignment — current S01 asserts ≤25s; v2's path is slower (~5s + nttDepartureTimeout); either tighten NTT for JOINING-window replacements or relax S01 assertion (decide E3).
- Configuration mismatch fail-fast (boot-time validation).
- `MembershipDecision` event stream replacement — clean emission point in v2 (interface during E2).
- Configured-size change observation race (sub-second window during commit propagation; idempotent + self-correcting).

## 8. Commits on this branch (newest first)

```
85470d6cf docs(spec): membership v2 finalized — trust model, drain consensus-unavailable caveat, DrainRequestKey schema (HLC), configured-size observation race
f3b270790 docs(spec): membership architecture v2 foundation — derive-from-reality + NTT pattern (scenarios 1, 2/2a, 5 settled)
b96619ea2 docs: session handover 2026-05-28 — cluster-B wedge layered stack; SWIM-ungate + slot-flap-reclaim fixed, resurrection now dominant (#230)  ← inherited from rc1
```

## 9. Relationship to rc1

Both branches are alive and serve different purposes:

- **`release-1.0.0-rc1`** continues with the current architecture + this session's narrower fixes (paused-sync, SWIM-ungate, slot-flap-reclaim, charter refresh). Those fixes are correct at their own layers and bank real progress; the next dominant bug on rc1 is the SWIM resurrection (per the rc1 handover). RC1 either patches resurrection separately or ships with the chaos suite flaky, depending on your call.
- **`experimental/membership-redesign`** is the structural answer — the spec captures the redesign that eliminates the bug class. Implementation is RC2 scope; it supersedes the topology-management layer of `membership-architecture-spec.md` when complete.

Neither branch should be pushed yet. Decision on rc1 release path (with-or-without-resurrection-patch) and timing of v2 implementation are operator/release decisions.

## 10. References

- **Spec:** `aether/docs/specs/membership-architecture-v2-spec.md` (this branch).
- **Rc1 layered-stack diagnosis** (the empirical motivation for the redesign): `aether/docs/internal/progress/session-handover-2026-05-28.md`.
- **Predecessor spec** (the model being superseded for topology management): `aether/docs/specs/membership-architecture-spec.md`.
- **Memory:** `[[project_cluster_b_wedge_layered_stack]]`, `[[project_membership_v2_redesign]]`.

## 11. For the next session

1. Read the spec front-to-back (~17 sections, designed to be self-contained).
2. Decide RC1 release path independently (resurrection patch or ship-with-flake).
3. Start implementation at E1 (introduce NTT + LocalQuorumWatcher observation-only). The chaos suite is the validation oracle for E3 / E4.
4. The implementation is "subtractive in effect, additive in approach" — new components introduced alongside old, validated, then old deleted. Don't delete first.

The intellectual heavy-lifting is done. The implementation is mechanical-but-substantial work over multiple sessions.
