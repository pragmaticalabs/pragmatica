<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
See LICENSE in the repository root for full terms.
-->

# Slot-Based Membership Convergence Specification

**Status:** Reviewed — implementation-ready (design decisions resolved in review, 2026-05-25)
**Date:** 2026-05-25
**Branch target:** `release-1.0.0-rc1`
**Owners:** membership / auto-heal (CTM) + FSM membership reducer
**Issue:** #230 (residual convergence defects); forward-pointer to #234 (atomic supersede for graceful migration — out of scope here)
**Related specs:** [`membership-architecture-spec.md`](membership-architecture-spec.md) (Layer model, §16 S01–S20 oracle), [`cluster-convergence-reconciler-spec.md`](cluster-convergence-reconciler-spec.md) (collapsed lifecycle alphabet)

> **Detection vs. convergence.** Failure *detection* (a virtual-thread scheduler swap that makes φ-accrual / SWIM departure fire promptly) is already fixed and is **not** in scope. This spec addresses what happens *after* a death is detected: the cluster over-counts ON_DUTY healthy cores and the generation does not quiesce. These are **convergence** defects.

---

## 1. Problem & Root Cause

### 1.1 Observed failure (suite-02 chaos auto-heal)

After a chaos kill + auto-heal cycle, two assertions fail:

- **(a) Over-count.** The cluster reports `coreCount = 7` against a target of `5` healthy ON_DUTY cores.
- **(b) No quiescence.** The cluster generation `did not quiesce within 90s` — generation churns because membership keeps mutating.

### 1.2 Three defects feed it (cited current behavior)

**Defect #1 — Fuzzy, SWIM-derived headcount with no structural cap.**

The operator-visible `coreCount` on `/api/cluster/topology` is computed at read-time from `MembershipView.onDutyPeers()` intersected with a transport-reachability snapshot, **not** from any bounded slot set:

- `ClusterTopologyRoutes.assembleFromTopologyManager` → `reachableOnDutyCount(...)` at `ClusterTopologyRoutes.java:160-162`; the snapshot path repeats it at `:196-198`.
- `reachableOnDutyCount` iterates `view.onDutyPeers()` and counts self + reachable peers (`ClusterTopologyRoutes.java:231-243`). When the reachability snapshot is absent it counts **all** KV ON_DUTY peers (`isReachable` returns `true` on `Option.none()`, `:245-247`).
- The underlying ON_DUTY derivation is `SnapshotMembershipView.healthyOnDutyCount()` = members whose `lifecycle == ON_DUTY` AND `healthHint == HEALTHY` (`SnapshotMembershipView.java:46-52`) and `onDutyMemberIds()` (`:36-43`).

CTM's deficit math is driven by the same SWIM-emergent count:

- `reconcileActive`: `actual = snapshotHealthyOnDutyCount()`, `rawDeficit = configured - actual - joining - liveSlots` (`ClusterTopologyManagerRecord.java:823-827`).
- `snapshotHealthyOnDutyCount()` reads `MembershipView::healthyOnDutyCount` and **falls back to `observer.clusterSize()`** when no snapshot exists (`:227-233`) — `clusterSize()` includes SWIM-faulty nodes during the snapshot gap.

Nothing in this pipeline structurally caps the count at `clusterSize`. A stale-but-still-ON_DUTY corpse and its freshly-provisioned replacement are **both** counted until the corpse's KV entry transitions to STOPPED. Result: `actual` momentarily reads `> configured`, CTM may both over-provision (replacement dispatched while corpse still counts toward neither deficit-coverage nor surplus consistently) and under-reap (surplus handling waits on live slots / terminations, `:1076-1097`).

**Defect #2 — Slow removal of the dead (graceful-drain on a corpse).**

CTM termination always routes through graceful drain, even for a hard-killed node:

- `terminateSingleNode` calls `writeDrainingAtom(nodeId)` then `drainCoordinator.awaitDrainAck(nodeId, timeout)` (`ClusterTopologyManagerRecord.java:1212-1218`), where `timeout = autoHealConfig.provisioningTimeout()`.
- `writeDrainingAtom` → `lifecycleWriter.requestDrain` routes `ForceDrain` through the sovereign FSM (`:1244-1249`), whose `applyForceDrain` on an ON_DUTY peer calls `enterDraining` → writes `DRAINING` + `DrainDeadline` + emits `InvokeDrain` (`ClusterMembershipReducer.java:365-374`, `:436-445`).
- A corpse cannot ack. The slot is occupied for the full drain timeout before `proceedToTerminate` → `writeDecommissionedAtom` (`:1230-1268`) finally writes STOPPED.

This path is reached for **surplus reaping** (`handleSurplus` → `terminateNodes` → `terminateSingleNode`, `:1105-1129`). Note that *detected failures* of ON_DUTY peers do **not** go through CTM termination — they go through the reducer's `onDutyToStopped` gated by the aggregator (`ClusterMembershipReducer.java:174-187`). So defect #2 specifically bites when CTM tries to **reap an over-counted ghost as surplus**: it drains a node that is already dead, holding the slot open and prolonging the overlap that defect #1 created.

**Defect #3 — No fence binding a replacement to the slot/predecessor it supersedes.**

`ProvisioningSlotValue` carries only `(spawnedAtMs, deadlineMs, Option<NodeId> assignedNodeId)` (`AetherValue.java:1504-1522`). Slots are keyed by a random UUID minted per provision (`ProvisioningSlotKey.provisioningSlotKey(UUID.randomUUID().toString())`, `ClusterTopologyManagerRecord.java:1295`). There is:

- **No identity** linking a slot to "the capacity unit it represents" — each provision wave mints fresh UUIDs.
- **No epoch / generation** on the slot. A late lifecycle write for a superseded occupant cannot be rejected as stale because nothing records which occupant is current.
- **No `supersededNodeId`** — a replacement does not know whose slot it fills.

### 1.3 Why the three compound

Detection now fires fast, but the *count* the cluster converges toward is SWIM-emergent and uncapped (defect #1). The one mechanism that could remove the stale occupant promptly (CTM reap) instead graceful-drains a corpse (defect #2), and there is no fence to reject the stale occupant's lingering lifecycle writes (defect #3). The generation keeps re-projecting as ON_DUTY membership flickers, so it never quiesces.

---

## 2. The Invariant

> **A cluster of declared size `S` has exactly `S` provisioning slots. Each slot has at most one ON_DUTY occupant. The operator-visible healthy-ON_DUTY headcount is the number of slots with exactly one healthy occupant — never more than `S`. CTM converges occupants toward "exactly one healthy occupant per slot."**

This replaces the current "count whatever SWIM says is ON_DUTY-and-reachable" rule (§1.2 defect #1) with a structurally-bounded, slot-derived count.

---

## 3. Design

Three locked decisions (D1, D2, D4) plus the **remove-then-add** failure-replacement rule. (D3 is reserved in the reconciler spec's numbering; not used here.)

### 3.1 D1 — Slot-based headcount authority

**Decision:** The cluster owns exactly `clusterSize` (= `ClusterConfigValue.coreCount`) durable provisioning slots. Each slot is a stable capacity unit with a stable identity (slot index `0..S-1`), not a transient per-wave UUID. A slot's occupant is the node currently filling it. The membership headcount is **slot-derived**: count slots whose occupant is ON_DUTY+healthy.

**Concrete touch-points & current state:**

- **Slot identity (changes).** Today slots are UUID-keyed and transient (`ClusterTopologyManagerRecord.java:1295`); created two-phase UNASSIGNED→ASSIGNED (`:1276-1345`) and deleted on ON_DUTY arrival (`deleteCompletedSlotAtomsForNode`, `:268-290`) or expiry (`deleteExpiredSlotAtoms`, `:1347-1364`). Under D1 the slot set is **stable and sized to `clusterSize`**: slots are not deleted when an occupant reaches ON_DUTY; they persist and record their current occupant. A slot is *empty* (no live occupant) or *occupied* (occupant ON_DUTY / JOINING / DRAINING).
- **Headcount read path (changes).** `reachableOnDutyCount` (`ClusterTopologyRoutes.java:160-162,196-198,231-243`) becomes slot-derived: count slots whose occupant is ON_DUTY+healthy-and-reachable. See §6 for the migration of every reader.
- **CTM deficit math (changes).** `reconcileActive` (`:813-880`) recomputes deficit as `emptySlots - liveInFlightFills` rather than `configured - actual - joining - liveSlots`. `snapshotHealthyOnDutyCount()` (`:227-233`) is replaced by a slot-occupancy count whose ceiling is `clusterSize` by construction.

**[ASSUMPTION A1]** Slot count tracks `ClusterConfigValue.coreCount`. On operator scale (`setDesiredSize` → `writeDesiredCoreCount`, `:167-209`) the slot set is resized: scale-up adds empty slots, scale-down removes the highest-index empty/least-loaded slots. The slot-resize writer is a new CTM responsibility — see §5.4.

### 3.2 D2 — Dead-node fast-path (no graceful drain for failures)

**Decision:** A *detected-dead* node (`SwimDeparted` or `TransportUnreachable` past the reachability gate) transitions **straight to STOPPED via the FSM detection reducer cell**. CTM does **not** graceful-drain failures. `DRAINING + awaitDrainAck` is reserved for **operator** drain and **scale-down** only.

**Concrete touch-points & current state:**

- **Reducer failure cells already go straight to STOPPED.** `applyJoining`: `SwimDeparted`/`TransportUnreachable` → `joiningToStopped(..., FORCED)` (`ClusterMembershipReducer.java:162-167`). `applyOnDuty`: `SwimDeparted` → `onDutyToStopped` ungated; `SwimFaulty`/`TransportUnreachable` → `onDutyToStopped` **gated** by `ReachabilityGate.isConfirmedUnreachable` (`:171-189`). This is correct and unchanged: detected failures already bypass DRAINING.
- **The defect is on the CTM reap side.** `terminateSingleNode` unconditionally graceful-drains (`:1212-1218`). Under D2, CTM must distinguish:
  - **Operator drain / scale-down** of a *healthy* occupant → keep `requestDrain` + `awaitDrainAck` (graceful).
  - **Reaping a slot whose occupant is already detected-dead** → CTM must **not** drain. The dead occupant is removed by the reducer's STOPPED write (above); CTM's job is only to free the slot (clear occupant) and, if the instance still exists cloud-side, best-effort `lifecycleManager.terminateNode` without awaiting a drain ack.
- **New CTM predicate.** Before `terminateSingleNode` graceful-drains, CTM checks the occupant's lifecycle: if already STOPPED (or detected-dead per the membership view), skip the drain and go straight to slot-free + best-effort cloud reap. Reuse the tombstone shape already present in `tombstoneAssignedNodeOnExpiry` (`:1371-1392`), which issues `ForceDecommission(FORCED)` + best-effort `terminateNode` with no drain ack — that is exactly the dead-node reap path, generalized.

**Result:** failures are remove-then-add (§3.4); only graceful operator/scale-down flows touch `awaitDrainAck`.

### 3.3 D4 — Slot fence (`occupantEpoch` + optional `supersededNodeId`)

**Decision:** Add a monotonic `occupantEpoch` (a `long`, slot-local, incremented on each occupant assignment) and an optional `supersededNodeId` to `ProvisioningSlotValue`. A lifecycle/slot write for an occupant whose epoch is **less than** the slot's current `occupantEpoch` is **rejected** (no-op + audit). This fences out a stale corpse's lingering writes after its slot has been re-occupied.

**Concrete touch-points & current state:**

- **Schema (changes).** `ProvisioningSlotValue` record (`AetherValue.java:1504-1522`) gains `long occupantEpoch` and `Option<NodeId> supersededNodeId`. See §4.
- **Serializer (changes).** `serializeProvisioningSlot` emits 3 pipe-delimited fields (`KVStoreSerializer.java:248-251`); add two fields + the matching deserialize arm (`deserialize`, slot case keyed `"provisioning-slot"` at `:171`). See §7.
- **Fence enforcement point (new).** The reducer's promote-to-ON_DUTY (`joiningToOnDuty`, `ClusterMembershipReducer.java:399-411`) and slot-claim (`enterJoining`, `:388-397`) arms must consult the slot's current `occupantEpoch` before writing a lifecycle transition for a peer bound to that slot. A write whose bound epoch `< slot.occupantEpoch` is dropped. Because the reducer is pure and per-peer (it does not read the slot map), the fence is applied at the **wiring layer** that resolves the reducer `Outcome` into KV commands (see `MembershipFsm.resolveLifecycleWrites`, referenced in `ClusterMembershipReducer.java:566-568`) — or the occupant-epoch is threaded onto the FSM state. See §5.3 + open question OQ3.

### 3.4 Failure replacement = remove-then-add (NOT atomic swap)

**Decision:** On a detected failure, the sequence is:

1. Reducer fast-STOPPED the dead occupant (D2) → its lifecycle is terminal.
2. CTM observes the slot's occupant is STOPPED → **frees the slot** (clears occupant, increments nothing yet) and best-effort cloud-reaps the dead instance (no drain ack).
3. CTM observes an **empty slot** → fills it: mints a provision, assigns the new occupant, **increments `occupantEpoch`**, sets `supersededNodeId = <dead occupant>`.
4. New occupant joins JOINING → ON_DUTY; the slot now has exactly one healthy occupant.

There is **no** window where the slot has two ON_DUTY occupants, because step 1 makes the predecessor terminal before step 3 assigns the successor. The over-count (defect #1) cannot recur: the slot count is `S` and each slot contributes at most one to the headcount.

---

## 4. Slot Model — Schema Change & Fence Semantics

### 4.1 Key (unchanged shape, changed lifecycle)

`ProvisioningSlotKey(String slotId)` with prefix `provisioning-slot/` (`AetherKey.java:1278-1299`). Under D1, `slotId` becomes a **stable slot index** (`"0".."S-1"`) rather than a per-wave `UUID`. Stable ids let CTM address "slot N" idempotently across waves and leader handovers.

**Decided (OQ1):** stable integer slot ids `"0".."S-1"` — idempotent "fill slot N" addressing (per-wave UUID minting is non-idempotent) and the `clusterSize` cap is visually obvious in KV dumps.

### 4.2 Value (changed)

Current (`AetherValue.java:1504-1522`):

```
record ProvisioningSlotValue(long spawnedAtMs, long deadlineMs, Option<NodeId> assignedNodeId)
```

Proposed:

```
record ProvisioningSlotValue(long spawnedAtMs,
                             long deadlineMs,
                             Option<NodeId> assignedNodeId,   // current occupant (== "assigned")
                             long occupantEpoch,               // monotonic, slot-local; 0 when empty/never-occupied
                             Option<NodeId> supersededNodeId)  // occupant this assignment replaced; none() on first fill
```

- `occupantEpoch` starts at `0` for an empty slot, increments to `1` on first occupant, `2` on the first replacement, etc. Strictly per-slot monotonic.
- `supersededNodeId` records the predecessor for audit + remove-then-add traceability; `none()` on first fill.
- Compatibility constructors preserve existing 2- and 3-field call sites (mirror the `NodeLifecycleValue` backward-compat constructor pattern, `AetherValue.java:706-729`), defaulting `occupantEpoch = 0`, `supersededNodeId = none()`.

### 4.3 Fence semantics

Let `slot` be the `ProvisioningSlotValue` for the slot a peer is bound to, and `boundEpoch` the epoch the peer was admitted under.

- **Lifecycle write accepted** iff `boundEpoch == slot.occupantEpoch` AND `slot.assignedNodeId == peer`.
- **Lifecycle write rejected (no-op + audit)** iff `boundEpoch < slot.occupantEpoch` (the peer is a superseded predecessor) OR `slot.assignedNodeId != peer` (the peer is not this slot's occupant).
- A `boundEpoch > slot.occupantEpoch` is **impossible by construction** (epoch only advances via CTM assignment, which is the single writer of slot occupancy) — if observed, it is a bug and should surface per the reducer's `illegal` convention (`ClusterMembershipReducer.java:642-648`).

---

## 5. CTM Convergence Algorithm (redefined)

### 5.1 Slot-occupancy model

CTM maintains (derived from the KV slot map via `slotReader`, `ClusterTopologyManagerRecord.java:85,120`):

- `slots: Map<slotIndex, ProvisioningSlotValue>` — exactly `clusterSize` entries.
- For each slot, classify occupancy:
  - **HEALTHY** — occupant present, lifecycle ON_DUTY, healthHint HEALTHY.
  - **FILLING** — occupant present, lifecycle JOINING (in-flight) OR a live provision in flight.
  - **DEAD** — occupant present but lifecycle STOPPED (or detected-dead per membership view).
  - **EMPTY** — no occupant.

`headcount = count(slots where occupancy == HEALTHY)`. By construction `0 ≤ headcount ≤ clusterSize`.

### 5.2 Provision-to-fill-empty (replaces deficit math)

Replaces `reconcileActive` deficit computation (`ClusterTopologyManagerRecord.java:813-880`) and `handleDeficitFromConverged` / `handleDeficitDuringReconciling` (`:1034-1073`, `:981-1032`):

```
on reconcile (phase == NORMAL, auto-heal enabled, stability + circuit + backoff gates pass):
  for each slot in slots:
    if occupancy(slot) == DEAD:
        freeSlot(slot)           # D2: clear occupant, best-effort cloud reap, NO drain ack
    # freeSlot leaves the slot EMPTY for the next pass (or same pass after re-read)
  emptySlots   = slots where occupancy == EMPTY
  fillingSlots = slots where occupancy == FILLING
  toFill = emptySlots not already covered by an in-flight fill
  for each slot in toFill (bounded by MAX_WAVE_SIZE = 5, ClusterTopologyManagerRecord.java:105):
    provisionIntoSlot(slot)      # mint provision; on success assign occupant,
                                 # occupantEpoch++, supersededNodeId = prior occupant
```

`freeSlot` reuses the dead-reap shape from `tombstoneAssignedNodeOnExpiry` (`:1371-1392`): `ForceDecommission(FORCED)` for the dead occupant (idempotent if already STOPPED) + best-effort `lifecycleManager.terminateNode` with no drain ack. The stability window (`stabilityElapsed`, `:929-935`), circuit breaker (`provisioningCircuitTripped`, `:397-409`), and backoff (`provisioningBackoffActive`, `:411-413`) gates are **retained** unchanged — they still protect against provisioning storms.

### 5.3 Fence wiring (D4)

`provisionIntoSlot` is the **single writer** of slot occupancy and the **only** place `occupantEpoch` advances. When CTM assigns an occupant (extends `assignProvisioningSlot`, `:1319-1333`) it stamps `occupantEpoch = prior + 1` and `supersededNodeId = prior occupant`. The new occupant's `boundEpoch` is propagated to the FSM so the reducer's promote arm can be fenced (§3.3). See OQ3 for where `boundEpoch` lives (FSM `Joining` state vs. re-read from slot map at resolve time).

### 5.4 Reap-excess (scale-down) — graceful retained

Operator scale-down (`setDesiredSize` shrinks `coreCount`, `:167-175`) removes slots. The occupants of removed slots are **healthy** and serving, so they get the **graceful** path: `terminateSingleNode` → `requestDrain` → `awaitDrainAck` (`:1212-1218`) unchanged. This is the legitimate use of DRAINING per D2. `handleSurplus` (`:1076-1130`) is re-expressed as "remove the highest-index slots and drain their occupants," reusing `selectNodesForTermination` ordering (`:1132-1144`).

### 5.5 Quiescence

Generation quiesces because: (a) the headcount is slot-bounded and stops flickering once each slot has one healthy occupant; (b) dead occupants are removed promptly (D2, no drain wait); (c) stale writes from a superseded predecessor are fenced (D4), so the generation projector stops re-projecting on corpse-driven lifecycle churn.

---

## 6. Headcount-Authority Migration (what reads change)

Every reader of the SWIM-emergent count must move to the slot-derived count or be reconciled. Readers found via grep on `coreCount` / `MembershipView` / `healthyOnDutyCount` / `onDutyMemberIds`:

| Reader | File:line (current) | Change |
|---|---|---|
| Topology REST `coreCount` | `ClusterTopologyRoutes.java:160-162, 196-198` | Slot-derived: count HEALTHY slots. `reachableOnDutyCount` (`:231-243`) becomes a slot-occupancy reducer; reachability snapshot still consulted per-occupant. |
| CTM deficit `actual` | `ClusterTopologyManagerRecord.java:227-233, 823-827` | `snapshotHealthyOnDutyCount()` replaced by HEALTHY-slot count (§5.1). |
| CTM surplus / selection | `:1076-1144` | Re-expressed as slot-removal (§5.4). |
| `MembershipView.healthyOnDutyCount` | `SnapshotMembershipView.java:46-52`; interface `MembershipView.java:50-51` | **Reconciled, not removed.** The generation snapshot still projects lifecycle from KV; the view remains the input to slot-occupancy classification. The view is *not* the authoritative count any more — the slot map is — but the view supplies per-occupant lifecycle+health that slot classification consumes. |
| `MembershipView.onDutyMemberIds` / `joiningCount` | `SnapshotMembershipView.java:36-43`; `MembershipView.java:102-107` | Retained as inputs to occupancy classification (FILLING uses `joiningCount`-equivalent per-slot). |
| Generation projector (`coreCount` consumers) | `ClusterGenerationProjector.java`, `GenerationSnapshotPublisher.java`, `SnapshotMembershipView` | Snapshot continues to carry `desiredCoreSize`; slot occupancy is reconciled against it. No projector signature change required if slots are read alongside the snapshot. |
| Metrics / dashboard | `aether-metrics/ClusterSyncCollector.java`, `node/DashboardMetricsPublisher.java` | Report slot-derived headcount + per-slot occupancy for operator visibility. |
| CLI / export | `cli/.../ClusterScaleCommand.java`, `ClusterExportCommand.java` | Read the same REST `coreCount`; no change beyond the REST fix. |

**Reconciliation rule for MembershipView:** the read-time SWIM-derived ON_DUTY derivation is **kept as a per-occupant input** (it answers "is the node filling slot N healthy?") but is **no longer the headcount authority** — the slot map caps and defines the count. This is the minimal change: we do not rip out the SWIM/health projection, we demote it from "the count" to "an input to slot classification."

---

## 7. Backward-Compat / Envelope & Codec

### 7.1 Codec change (required)

`ProvisioningSlotValue` gains two fields → `KVStoreSerializer` must change:

- `serializeProvisioningSlot` (`KVStoreSerializer.java:248-251`) appends `PIPE + occupantEpoch + PIPE + supersededNodeId.map(NodeId::id).or("")`.
- The matching deserialize arm (slot case keyed `"provisioning-slot"`, dispatch at `KVStoreSerializer.java:171`) must tolerate **both** the 3-field legacy form (default `occupantEpoch = 0`, `supersededNodeId = none()`) and the 5-field new form. Fail-open-with-default on the trailing fields, mirroring the `NodeLifecycleValue` trailing-`version` compatibility note (`AetherValue.java:672-696`).

### 7.2 Envelope version — NO bump required (decided, OQ5)

`ProvisioningSlotValue` is a **KV-Store atom** serialized by the hand-written `KVStoreSerializer` (`serializeProvisioningSlot`, `KVStoreSerializer.java:248-251` — verified, 3 pipe-delimited fields), **not** part of the slice envelope (generated factory/adapter/manifest code per `envelope-versioning.md:1-50`). Adding fields changes the `KVStoreSerializer` wire format, governed by per-value backward-compat (§7.1), **not** `ENVELOPE_FORMAT_VERSION` — which stays **frozen at 1000** (`ManifestGenerator.java:34`; a prior 1001→1000 revert under "no bumps until GA", PR #229). CLAUDE.md invariant #3 governs `SliceProcessor`/`FactoryGenerator` codegen output, not arbitrary KV atom fields. **Decision: no envelope bump; ship the backward-compatible serializer field addition (§7.1).**

### 7.3 Live-state migration

A cluster mid-upgrade may hold 3-field slot atoms. The deserialize default (§7.1) reads them as `occupantEpoch = 0`. Because D1 also changes slot *identity* (UUID → stable index), the cleanest migration is: on leader activation (`activate` → `rehydrateInFlightSlotsFromKV`, `ClusterTopologyManagerRecord.java:512-555`), **wipe legacy UUID-keyed slots** and re-seed `clusterSize` stable-index slots from current occupancy. Legacy slots are transient anyway (deleted on ON_DUTY/expiry today), so wiping them loses no durable truth — the durable membership truth is the `NodeLifecycleKey` ON_DUTY entries, from which the reseed reconstructs occupancy. The reseed binds the S occupants with **lowest `observedCoreEpoch`** (oldest first; tie-break NodeId lexical for determinism) to slots `0..S-1`; surplus occupants are reaped, so the reseed doubles as the convergence-collapse point (decided, OQ4).

---

## 8. Test Oracle

### 8.1 Map to `membership-architecture-spec.md` §16 (S01–S20)

The slot model must not regress the detection-oracle scenarios. New/changed expectations:

| S-ID | Scenario | Slot-model expectation (delta from §16) |
|---|---|---|
| S01 | JOINING-window kill | Slot occupant in FILLING → reducer `joiningToStopped` (FORCED) makes slot DEAD → CTM frees → refills. Headcount never exceeds `S`. |
| S02 | ON_DUTY single non-leader kill | Occupant HEALTHY→DEAD (reducer `onDutyToStopped`, gated). CTM frees slot (D2, **no drain**) then refills. **Replaces** the old graceful-drain reap. Headcount returns to `S` without transient `S+1`. |
| S03 | Two simultaneous non-leader kills | Two slots DEAD → freed → refilled in parallel (bounded by `MAX_WAVE_SIZE`). |
| S05/S06 | Partition + heal | Majority side: minority slots DEAD → freed → refilled. On heal, superseded predecessors are **fenced** (D4) — their late ON_DUTY writes are rejected, so no `S+1` over-count on rejoin. |
| S07 | Graceful operator drain | **Unchanged** — DRAINING + awaitDrainAck retained (D2 carve-out, §5.4). |
| S08/S09 | Drain timeout / drain during partition | **Unchanged** — graceful path. |
| S11/S12 | Restart inside/outside TTL | STOPPED is terminal; new incarnation fills an EMPTY slot with `occupantEpoch++`. Old occupant's writes fenced. |
| S15 | Cold-start formation | `S` empty slots fill to `S` healthy occupants; headcount monotonically approaches `S`, never overshoots. |
| S18 | Leader kill + re-election | New leader rehydrates slots (§7.3); dead leader's slot DEAD→freed→refilled. |

### 8.2 Suite-02 assertions (the actual #230 residual)

- **Over-count oracle:** after chaos kill + auto-heal, assert `coreCount == 5` (never `> 5` transiently beyond a bounded window). Slot cap (D1) makes `> S` structurally impossible at read time; assert the read-time invariant directly.
- **Quiesce oracle:** assert the cluster generation quiesces within 90s (the failing assertion). D2 (prompt dead removal) + D4 (fence) eliminate the corpse-driven re-projection churn.

### 8.3 New unit tests

- **Fence:** a superseded occupant's lifecycle write (`boundEpoch < slot.occupantEpoch`) is a no-op (mirror the existing reducer cell tests, e.g. `ClusterMembershipReducerTest`).
- **Slot classification:** DEAD/EMPTY/FILLING/HEALTHY transitions from a fabricated slot map.
- **D2 reap:** CTM frees a DEAD slot **without** calling `awaitDrainAck`; CTM scale-down of a HEALTHY occupant **does** call `awaitDrainAck`. (Extend `ClusterTopologyManagerScaleDownDrainTest`.)
- **Codec round-trip:** 3-field legacy atom deserializes with defaults; 5-field round-trips (extend the slot KV mirror tests, e.g. `ClusterTopologyManagerProvisioningSlotKvMirrorTest`).
- **Headcount cap:** REST `coreCount` never exceeds `clusterSize` given a slot map with a DEAD + a fresh HEALTHY occupant for the same slot.

---

## 9. Resolved Decisions (main-thread review, 2026-05-25)

- **OQ1 (slot identity) → stable integer ids `"0".."S-1"`.** Makes the `clusterSize` cap self-evident in KV dumps and gives idempotent "fill slot N" addressing — per-wave `UUID.randomUUID()` minting is non-idempotent and part of today's ghost churn. CTM is the single writer and rehydrates the slot map on handover, so "slot N" is collision-free; `occupantEpoch` (D4) fences any stale write to it. Drives §4.1 + §7.3.
- **OQ2 (FILLING dedup) → explicit in-flight marker on the durable slot.** Reuse the existing `spawnedAtMs`/`deadlineMs` fields as the FILLING marker: provision-start stamps them (and increments `occupantEpoch`) BEFORE calling the provider; provider success sets `assignedNodeId`; ON_DUTY → HEALTHY; deadline-with-no-ON_DUTY-occupant → reset slot to EMPTY + best-effort reap. "Slot has a JOINING occupant" alone is **insufficient** — the provision→JOINING gap is exactly where double-provisioning (the over-count) occurs, so the marker must exist from provision-start. Preserves today's two-phase UNASSIGNED→ASSIGNED semantics as slot *state*, not a per-wave UUID atom. Drives §5.2/§5.3, §3.4.
- **OQ3 (fence placement) → wiring layer.** The `occupantEpoch` fence is applied in `MembershipFsm.resolveLifecycleWrites` (which can read the slot map), keeping the reducer a pure `(state,event)→Outcome` with no slot-map dependency or new state field. Mirrors the existing F18 value-merge at the wiring layer. Drives §3.3/§5.3.
- **OQ4 (live migration) → wipe-and-reseed on leader activation, seniority by `observedCoreEpoch`.** On activation, wipe legacy UUID slots and re-seed `clusterSize` stable-index slots from current occupancy (slots are transient today → no durable loss; durable truth is the `NodeLifecycleKey` ON_DUTY entries). Bind the S occupants with **lowest `observedCoreEpoch`** (oldest first) to slots `0..S-1`; the rest → surplus → reaped — so the reseed doubles as the convergence-collapse point that squeezes out an existing over-count. **NodeId/KSUID-sort is explicitly REJECTED for seniority:** the integration test env mixes ordinal IDs (`aether-b-node-1..5`) with KSUID replacement IDs, so lexical NodeId-sort is not time-order and would mis-rank seniority in our own validation env. `observedCoreEpoch` is provider-agnostic; tie-break NodeId lexical for **determinism only**. Drives §7.3 + §5.
- **OQ5 (envelope) → NO `ENVELOPE_FORMAT_VERSION` bump.** `ProvisioningSlotValue` is a KV atom serialized by hand-written `KVStoreSerializer` (`serializeProvisioningSlot`, 3 pipe-delimited fields — verified), not slice-envelope codegen. The two new fields ship with a backward-compatible deserialize (3-field legacy → `occupantEpoch=0`, `supersededNodeId=none()`). CLAUDE.md invariant #3 governs `SliceProcessor`/`FactoryGenerator` codegen output, not arbitrary KV atom fields. Drives §7.1/§7.2.
- **OQ6 (dead-write ownership) → reducer owns dead→STOPPED; CTM never writes STOPPED.** Detected ON_DUTY/JOINING failures already transition to STOPPED in the reducer (`onDutyToStopped`/`joiningToStopped`). For a DEAD slot, CTM only frees the slot (clear occupant) + best-effort cloud reap with **no drain ack** — it must not issue the STOPPED lifecycle write (single-writer P4; avoids double-write races). Drives §3.2/§5.2.

### 9.1 Non-dependencies (guard against re-coupling)

Standardizing node IDs on KSUID may be worth doing separately (sortable, time-embedded, collision-resistant) but **MUST NOT become a hidden dependency** of the slot-binding rule — seniority is keyed on `observedCoreEpoch`, not ID format. **Spec action:** confirm `observedCoreEpoch` is populated on ALL join paths (bootstrap + CTM + each cloud provider); where unset/zero, seniority degrades to the deterministic NodeId tie-break for those occupants only.

---

## 10. Out of Scope → #234

**Atomic occupant supersede for graceful migration** (start a replacement ON_DUTY *before* draining a healthy predecessor, so a slot briefly holds two occupants by design under a controlled handoff) is **out of scope**. This spec's failure path is strictly remove-then-add (§3.4): the predecessor is terminal before the successor is assigned, so a slot never holds two ON_DUTY occupants. The atomic-swap variant — required only for zero-capacity-dip graceful migration — is tracked as **#234** and will extend the D4 fence (the `supersededNodeId` field is already provisioned in §4.2 to support it) with a two-occupant transitional slot state. Do not implement it here.

---

## 11. References

### Internal specs & docs
- [`aether/docs/specs/membership-architecture-spec.md`](membership-architecture-spec.md) — Layer model; §16 S01–S20 oracle (this spec's test contract).
- [`aether/docs/specs/cluster-convergence-reconciler-spec.md`](cluster-convergence-reconciler-spec.md) — collapsed 4-value lifecycle alphabet + StopReason sidecar.
- [`aether/docs/contributors/envelope-versioning.md`](../contributors/envelope-versioning.md) — envelope-version policy (relevant to OQ5).

### Code (current behavior, cited)
- `aether/aether-deployment/.../cluster/ClusterTopologyManagerRecord.java` — CTM deficit/reap/slot lifecycle.
- `aether/aether-deployment/.../membership/fsm/ClusterMembershipReducer.java` — per-peer reducer; failure → STOPPED cells.
- `aether/slice/.../kvstore/AetherValue.java` — `ProvisioningSlotValue`, `NodeLifecycleValue`.
- `aether/slice/.../kvstore/AetherKey.java` — `ProvisioningSlotKey`.
- `aether/slice/.../kvstore/KVStoreSerializer.java` — slot serialize/deserialize.
- `aether/aether-deployment/.../generation/SnapshotMembershipView.java` — ON_DUTY derivation.
- `aether/node/.../api/routes/ClusterTopologyRoutes.java` — REST `coreCount`.
- `integrations/consensus/.../topology/MembershipView.java` — view interface.
- `jbct/slice-processor/.../generator/ManifestGenerator.java:34` — `ENVELOPE_FORMAT_VERSION = 1000` (frozen).

---

**END OF SPECIFICATION**
