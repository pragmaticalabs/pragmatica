<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Membership NTT Convergence — Collapse the Unification Parallel Back onto NTT (2026-05-30)

**Authoritative design of record:** `aether/docs/specs/membership-architecture-v2-spec.md` ("Membership Architecture v2 — Derive-from-Reality"). **NTT (`NodeTopologyTracker`) is THE membership tracker.**
**Superseded:** `aether/docs/specs/membership-unification-spec.md` — a later proposal that built the smoothing tweak as a *parallel type in `swim`* + a second `MembershipView` consumer + a consensus injection. That was a design explosion, not a design. This doc collapses it back.

## 0. The reframe (why this is a collapse, not a rewrite)
The swim `MembershipTracker` and NTT are **the same design with two change-detection mechanisms**. v2-architecture §6 NTT used per-peer `ScheduledFuture` departure timeouts; the intended *tweak* was to replace those with a **1-s periodic membership sample + delta detection** (keeping NTT's `currentMembers` set and `onReconcileNeeded` contract). That tweak got mis-built as a separate `swim.MembershipTracker` type, a new module placement, `TrackerBackedGenerationSnapshotSource`, a consensus injection, and a second spec. **The algorithm is good and tested; only its placement/wiring/spec are wrong.**

**Key enabler — v2-architecture §4 two counts:**
- `localQuorumCount` = QUIC `connectedNodeCount` (+self) → **consensus quorum / quorum-loss self-drain.**
- `clusterMembershipCount` = SWIM set via **`NodeTopologyTracker.currentMemberCount()`** → **CTM/topology only.**

⇒ **Consensus never needs to read NTT.** So NTT stays in `aether-deployment`; the entire "put the tracker in swim so consensus can read it" rationale evaporates. Collapsing **shrinks** the footprint (−1 type, −1 module touched, −1 `MembershipView` consumer, −1 spec, −1 consensus injection).

## 1. Settled decisions
- **D-A:** v2-architecture (NTT) authoritative; unification superseded/archived.
- **D-B:** Keep hysteresis (good). Mapping preserved: **up = fast (K≈2 samples)**, **down = `nttDepartureTimeout`** (encodes NTT's per-peer departure semantics in the sampled model). Sample tick = 1 s.
- **D-C:** **Full collapse** — port the FSM into NTT, delete the swim type + consensus injection + second spec. No half-measures (a surviving parallel type *is* the explosion).
- **D-D:** Smoothing is folded into v2 spec §6 as an in-place amendment, not a new spec.

## 2. Design-independent bugs that stand regardless (the real "what went wrong")
These were severed by the unification work and must be fixed on the way:
1. **Seed-PEERS severed (the wedge).** v2 §4 + NTT header make NTT the "freshest provisioning seed-PEERS set," but `ClusterTopologyManagerRecord.provisionReplacement(failedPeer, clusterMembers)` ignores `clusterMembers` and seeds from `observer.topology()`∩`isHealthyPeer`. → DOA replacements (dead-host PEERS → QUIC NPE). Fix: seed from `NTT.currentMembers`.
2. **CDM reads the snapshot, not live membership** (`ClusterDeploymentState.activeNodes()` ← `ctx.snapshotSupplier()`) → stale-by-a-round. Fix: read NTT; snapshot stays for follower distribution only.
3. **`healthyOnDutyCount()`/`onDutyMemberIds()` return the full member set** (swim tracker) feeding CTM scale math. Removed when the swim tracker goes; NTT supplies `currentMemberCount()`.
4. **QUIC null-address NPE** (`SockaddrIn.setIPv4` on unresolved DNS) — guard regardless (defensive backstop once seeds are live).
5. **Lying comments / dead pipeline:** `ConsensusBridge`/`RabiaEngine` "QUIC path removed" (it's the live path under §4 — fix the comment, keep the path); gutted `AetherNode.membershipView()` comment; the orphaned `AggregatedReachabilitySnapshot` per-ping serialization + `ClusterSyncCollector` reachability pipeline.

## 3. v2 spec §6 amendment (smoothing mechanism — fold in place)
Replace NTT §6.2 "per-peer departure timer" mechanism with:
> NTT recomputes its member set on a **fixed 1-s sample tick** from the current SWIM health snapshot. A node **enters** after K_up consecutive healthy samples (K_up≈2, fast), **leaves** after K_down consecutive absent samples where `K_down = ceil(nttDepartureTimeout / sampleInterval)` (preserving the per-peer departure-timeout semantics). The set-delta is emitted **once** per stable transition, invoking `onReconcileNeeded`. QUIC reconnect is a fast up-bias; QUIC disconnect a fast down-bias — both colour the sample, neither bypasses hysteresis. Replaces the per-peer `ScheduledFuture` map (kills the timer-lifecycle-race class).

## 4. Implementation phases (delegate Java to jbct-coder; AetherNode wiring direct)
- **P1 — NTT absorbs the smoothed mechanism.** Port the `swim.MembershipTracker` sample+delta+hysteresis FSM into `NodeTopologyTracker` (deployment), replacing per-peer timeouts; preserve `currentMembers()/currentMemberCount()/onReconcileNeeded` contract; inject a SWIM health-snapshot supplier for sampling; keep QUIC up/down bias taps. Unit tests (port `MembershipTrackerTest` cases onto NTT).
- **P2 — Re-point consumers to NTT.** (a) CTM `provisionReplacement` seeds from `currentMembers`; (b) CDM `activeNodes()` membership from NTT live; (c) `GenerationSnapshotPublisher` memberSupplier → NTT; (d) quorum-loss self-drain → `localQuorumCount` (QUIC) per §4; (e) consensus quorum → `localQuorumCount` primary, remove tracker injection.
- **P3 — Delete parallels.** swim `MembershipTracker` + `MembershipTrackerConfig` + test; `TrackerBackedGenerationSnapshotSource`; revert consensus cold-start to the QUIC path (keep, fix the comments); AetherNode: delete `membershipTracker` construction, re-point taps/suppliers to NTT.
- **P4 — Dead-code + guards.** QUIC null-addr guard; remove orphaned reachability pipeline (`AggregatedReachabilitySnapshot` per-ping, `ClusterSyncCollector` reachability methods, `reachability-aggregator-spec.md`); fix lying comments; stale `LocalQuorumWatcher` doc refs.
- **P5 — Validate.** `mvn -pl aether/node -am clean install -Dmaven.test.skip=true`; module unit suites; Docker `--suites 00,02` on a clean host (cold-start → slice placement → leader-kill survive → auto-heal to 5 — the wedge must be gone because seeds come from NTT live membership).

## 5. Process guardrail (prevent recurrence)
**One design of record per subsystem, amended in place.** Evolve a mechanism *inside the owning component* (NTT) — never spawn a parallel type in a new module with a new spec. A change that introduces a second "who's alive" structure is the smell; stop there.

## 6. Status
HEAD `ef90af3ac`+`6a8c3cd3e` region (release-1.0.0-rc1). Reconciler drain guard + ULID + IdGenerator refactor + test re-source already landed and are KEEPERS (orthogonal to the collapse). Start at P1.
