<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Membership / Placement Split — Design Specification

> ✏️ **UPDATED — defers to [`cluster-topology-overhaul-spec.md`](cluster-topology-overhaul-spec.md) for the authority model.** Core decisions (membership out of the committed snapshot; presence-derived local membership; placement epoch fence) are CONSISTENT and partly live. NTT references now map to `PresenceSampler` / `MembershipFsm`; the M5 rejoin-convergence here aligns with the overhaul §5.9. Spec of record for the placement split.

**Status:** Draft for review · **Branch:** `release-1.0.0-rc1` · **Date:** 2026-06-01
**Supersedes the membership half of:** `cluster-generation-spec.md` (the `GenerationSnapshot` membership content)
**Builds on:** `membership-architecture-v2-spec.md` (derive-from-reality), the uncommitted terminal-removal rework.

## 1. Motivation

The cluster's **membership** (which nodes are alive) is currently propagated by committing it into the KV-Store: the leader projects a `ClusterGenerationSnapshot` (carrying `coreMembers`) and commits it through Rabia under `GenerationSnapshotKey.SINGLETON`; `TopologyObserver` reads that *committed* snapshot back and diffs it to emit `NodeRemoved`/`NodeJoined`. Consequence: **a departure is only observed by the deployment layer after a consensus commit succeeds.** When consensus is wedged (e.g. a Rabia `BatchId`-collision / backpressure stall), the snapshot `Put` never applies, so `NodeRemoved` never fires, the reconciler never runs, and the cluster cannot heal — the recurring "departure never commits" failure.

This violates the v2 principle that **departure is observed silence, derived locally** (membership-architecture-v2-spec §P2/§P4). Membership is *already* derived locally (`GenerationSnapshotPublisher.deriveMembersFromPresence()` = `ntt.currentMembers()`); it is then needlessly round-tripped through consensus.

## 2. Governing principle (the line that decides what belongs in KV)

> **Committed KV = external facts the core collectively governs and must agree on.** Placement: community/governor assignments, partition ownership, slice placement, spokesmen, `desiredCoreSize`. Consistency across the core is the *point*; these are not the cluster's own liveness.
>
> **Presence-derived, never KV = the cluster's own membership reality** — which nodes are alive. Every node observes this directly via SWIM/QUIC (NTT); committing it adds nothing but the consensus coupling that wedges departures.

Membership is *cluster state* (derivable from reality). Placement is *external facts the core authors* (not derivable from reality — must be agreed and durable). They have opposite storage rules, and the current `GenerationSnapshot` wrongly fuses them.

## 3. Current mechanism (what changes) — verified

**Producer** — `GenerationSnapshotPublisher` (leader-only; FSM `Disabled→Idle→Publishing→PublishingDirty`):
- Dirty triggers: `onMembershipDecision(any)`, `markDirty()` (NTT-eviction edge), `onLeaderGained`.
- `runApply()` → `projectFromKv()` → `cluster.apply(Put(GenerationSnapshotKey.SINGLETON, projected))` (consensus commit).
- `projectFromKv()` fills `coreMembers` from `deriveMembersFromPresence()` = `memberSupplier.get()` = `ntt.currentMembers()` (local); placement (`communities`, `partitions`, `spokesmen`, artifacts) from KV atom scans; `desiredCoreSize` from `ClusterConfigKey.CURRENT` (falling back to member count).

**`ClusterGenerationSnapshot` fields:** `epoch`, `desiredCoreSize`, **`coreMembers: Map<NodeId,CoreMember>`** *(MEMBERSHIP — to remove)*, **`nodesWithoutSlices: Set<NodeId>`** *(MEMBERSHIP — to remove)*, `communities`, `partitions` *(PLACEMENT — keep)*.

**Membership consumers of the committed snapshot (all re-source to local NTT):**
| Consumer | Site | Use |
|---|---|---|
| `TopologyObserver.publishMembershipDeltas` | `TopologyObserver.java:638-675` | diff `coreMemberIds` → `NodeJoined`/`NodeRemoved` |
| `TopologyObserver` quorum eval | `healthyActivePeerCount` etc. | count core members for quorum |
| `ClusterTopologyManagerRecord` | `:239`, `:247` | `currentMembershipView()` |
| `ClusterDeploymentState.activeNodes()` | `:632-643` | "which nodes can I place on" |
| `BootstrapModule` | `:348` | `seeded.coreMembers().containsKey(owner)` liveness check |

**Placement consumers (keep reading the slimmed snapshot):**
| Consumer | Site | Use |
|---|---|---|
| `ClusterDeploymentState.activeCommunityIds()` | `:649-653` | `snapshot.communities().keySet()` |
| `BootstrapModule` | `:240` | `seeded.partitions().get(CORE_PARTITION_ID)` |

**Placement is authoritative in individual atoms** (`SliceNodeKey`, `DhtPartitionOwnershipKey` w/ its own `ownershipTerm` fence, `GovernorAnnouncementKey`, `SpokesmanKey`, `NodeArtifactKey`, `ClusterConfigKey`); the snapshot is a consistent, epoch-stamped aggregate of them, and `BootstrapModule.projectFromCommittedAtoms()` can rebuild it. Option B (delete the snapshot, scan atoms for placement) was **rejected**: communities/partitions are a shared core responsibility where the consistent epoch-stamped aggregate has real value; a live atom scan would forfeit that consistency.

## 4. Target design

### 4.1 Membership — locally derived (NTT)
- Introduce a **local `MembershipView` backed by `ntt.currentMembers()`** (the leader and every node use their own). This becomes the membership source for the five consumers in §3.
- `TopologyObserver.publishMembershipDeltas` diffs the **NTT** membership (not the committed snapshot). Emissions stamped with **HLC + a per-node monotonic counter** instead of `logIndex = observedRabiaTerm` (consumers dedup on the new stamp; keep the `-1L` sentinel contract).
- `ClusterDeploymentState.activeNodes()` returns `ntt.currentMembers()` minus passive.
- `ClusterTopologyManagerRecord` membership reads switch to the NTT view.
- **Consistency:** the distributed control plane is removed — only the leader acts on membership (placement decisions), so its single local view is consistent by construction. Non-leader local views drive only local routing (self-correcting) and local quorum (correct by design). No committed membership view is needed.

### 4.2 Quorum evaluation — local presence
- `TopologyObserver` NORMAL-mode quorum count moves off `snapshot.coreMemberIds` to **local QUIC/NTT presence** (`localQuorumCount` semantics). This is the more correct signal for consensus liveness and removes the snapshot dependency from the `/health/ready` path. BOOTING fallback (`nodeStatesById`) is unchanged.

### 4.3 Placement — committed snapshot, slimmed
- Remove `coreMembers` and `nodesWithoutSlices` from `ClusterGenerationSnapshot` and the projector input.
- The snapshot remains the consistent, epoch-stamped aggregate of placement/config (`communities`, `partitions`, `spokesmen`, `desiredCoreSize`) for `activeCommunityIds()` and `BootstrapModule`. Its epoch/term remains the **fence against a stale (demoted) leader's placement writes**.
- `desiredCoreSize` fallback changes from `lifecycles.size()` to a config/`coreMax()` default (membership no longer available in the projector).

### 4.4 Publisher triggers — placement-change only
- **De-wire `onMembershipDecision → markDirty`**: membership changes no longer alter snapshot content, so they must not trigger a commit.
- Publisher marks dirty on **placement-relevant** changes: governor/community, partition-ownership, spokesman, `ClusterConfigKey` puts, and CDM placement mutations.
- A **departure still republishes** — but indirectly and correctly: NTT (local) → CDM evacuates the departed node's slices/community responsibilities → that placement change marks the snapshot dirty → commit records the *new placement*. Detection/decision is decoupled from consensus; only the durable placement record waits for consensus (as replicated state must).

### 4.5 Settle window — reuse `armedAtNanos` on leader-gain
- A freshly-promoted leader's NTT is cold (every peer initially looks absent). **Defer all CDM reconciliation** (provisioning *and* slice-evacuation) until presence converges.
- Reuse the existing `LeaderReconciler` cold-start grace: `armedAtNanos` + `nttDepartureTimeout × 1.5`, plus the `deficitSinceNanos` debounce. **Arm it on `onLeaderGained`** (today: cold-start only), and gate the slice-evacuation path on the same arm, not just provisioning.
- Rationale: `nttDepartureTimeout` is exactly the window NTT uses to confirm a departure — after it, every genuinely-alive peer has cancelled its departure timer (QUIC reconnect / probe-ack) and every dead one has expired, so presence is reliable. Existing placements keep running meanwhile (consensus already holds since the leader was elected); the only cost is a few seconds' delayed rebalance after failover, which is correct.

### 4.6 Failover sequence (target)
1. New leader elected (majority by definition).
2. Reads committed **placement** snapshot (`readPublishedSnapshot`, or `projectFromCommittedAtoms` fallback) — placement only; no membership read.
3. **Settle window** (§4.5): NTT presence converges.
4. Reconcile: for each placed responsibility whose owner is absent in the converged NTT view, reassign; fill to `desiredCoreSize` on available nodes. Resume publishing from the snapshot's epoch (incrementing the counter; the epoch fence rejects the demoted leader's late writes).

## 5. Event sequences (target)

### Departure (the path that wedges today — fixed)
1. Node N goes silent → SWIM FAULTY ∧ QUIC `livenessGone` → NTT evicts N (local).
2. `TopologyObserver` diffs NTT → emits `NodeRemoved(N)` **immediately** (no consensus commit).
3. Consumers act: `ClusterEventAggregator` → `NODE_FAILED`; forwarder/LB drop N; **leader CDM** evacuates N's slices/communities and **CTM** provisions a replacement if below `desiredCoreSize`.
4. CDM's placement mutation marks the snapshot dirty → committed (records the reassignment). *If consensus is wedged, detection + the decision still happen; only the durable placement record waits — no stall of departure visibility.*

### Node join / auto-heal
1. New node attaches (QUIC+SWIM) → NTT adds it → `TopologyObserver` emits `NodeJoined`.
2. Leader CDM places work; placement change → snapshot commit.

### Leader failover — see §4.6.

## 6. Change inventory

**Remove / slim:** `ClusterGenerationSnapshot.coreMembers`, `.nodesWithoutSlices` (+ `@Codec` regen); `GenerationSnapshotPublisher.deriveMembersFromPresence` + `memberSupplier`/`addressResolver` params; `SnapshotMembershipView` (membership projection); `KvBackedGenerationSnapshotSource.currentMembershipView` (membership path).
**Add:** NTT-backed `MembershipView` + wiring into `TopologyObserver`, `ClusterTopologyManagerRecord`, `ClusterDeploymentState.activeNodes`, `BootstrapModule` liveness check; HLC+counter stamping for `MembershipDecision`.
**Re-wire:** `TopologyObserver` quorum eval → local presence; publisher dirty triggers → placement-only; `LeaderReconciler` arm on `onLeaderGained` + gate evacuation.
**Keep untouched:** placement atoms + their fences; `activeCommunityIds`/`BootstrapModule.partitions` reading the slimmed snapshot; the `GenerationSnapshotKey` mechanism for placement.

## 7. Envelope / Codec
`ClusterGenerationSnapshot` is `@Codec`; removing two fields changes its serialized form. RC1 is unreleased → **no rolling-upgrade compatibility burden**; regenerate the codec and bump any snapshot format/version constant if present. (Not a slice-processor `ENVELOPE_FORMAT_VERSION` change unless codegen output structure shifts.)

## 8. Cutover plan (incremental, atop the uncommitted terminal-removal rework)
1. Add the NTT-backed `MembershipView` alongside the snapshot path (no consumer switch yet); unit-test it equals `ntt.currentMembers()`.
2. Switch `TopologyObserver.publishMembershipDeltas` + quorum eval to NTT; verify `NodeJoined`/`NodeRemoved` still fire (now consensus-independent). **Docker 02,12 here is the key proof** (single-instance, clean slate — see `feedback_check_orphan_runs_before_docker`).
3. Switch `ClusterTopologyManagerRecord`, `ClusterDeploymentState.activeNodes`, `BootstrapModule` to NTT.
4. Arm the `LeaderReconciler` grace on `onLeaderGained`; gate evacuation.
5. De-wire publisher membership trigger; slim the snapshot (`@Codec` regen); drop `SnapshotMembershipView` + the membership read path.
6. Full-suite Docker validation (15/15 target).

## 9. Tests
- Unit: NTT `MembershipView` correctness; `MembershipDecision` HLC/counter dedup; publisher republishes on placement-only triggers and **not** on membership-only changes; `LeaderReconciler` defers all reconciliation within the leader-gain grace and acts after it.
- Integration (Docker, single-instance): departure under wedged consensus still produces `NODE_FAILED` + slice evacuation (the regression this fixes); leader-kill failover reconciles after the settle window without churn; auto-heal restores `desiredCoreSize`.

## 10. Risks / non-goals
- **Risk:** placement commit still requires consensus — a wedged Rabia still blocks *durable* reassignment. **In scope only** to decouple *detection/decision*; the underlying consensus stall (BatchId-collision / node-5 backpressure) is a separate consensus-layer fix (see session-handover-2026-06-01 §4b).
- **Risk:** per-node membership divergence — bounded to local routing/quorum; control-plane is leader-only, so placement stays consistent.
- **Non-goal:** eliminating the placement snapshot (Option B) — rejected in §3.
- **Non-goal:** changing SWIM/QUIC/NTT detection mechanics (delivered by the terminal-removal rework).

## 11. Decision log
- **D1 Split** — membership out of committed snapshot; placement/config stays. *(approved)*
- **D2 Settle** — defer all CDM reconciliation one window on leader-gain, reuse `armedAtNanos` @ `nttDepartureTimeout × 1.5`. *(approved)*
- **D3 Local membership** — NTT-derived; quorum→local; leader single view consistent (control plane is leader-pinned); placement epoch fence guards stale leader; HLC+counter stamping. *(approved)*
- **D4 Slim, don't eliminate** — keep the snapshot as the consistent placement aggregate (Option A); reject Option B because communities/partitions are shared core-governed external facts needing a consistent view. *(approved)*
