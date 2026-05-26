<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Slot-Based Core Membership — Redesign (2026-05-26)

**Status:** Design-approved, implementation-ready.
**Supersedes:** OQ4 ("wipe-and-reseed on activation, seniority by `observedCoreEpoch`") in
[`slot-based-membership-convergence-spec.md`](slot-based-membership-convergence-spec.md). All other
decisions in that spec (D1 slot-based headcount, D2 dead-fast-free, durable integer slots `0..S-1`,
`occupantEpoch` fence) carry forward unchanged.

**Scope:** CORE nodes only. Worker nodes have their own membership scheme and are out of scope for
every rule below. Every predicate here MUST be gated on core role; a worker must never evaluate the
slot-binding or orphan-drain logic.

## 0. Why

The wipe-and-reseed reseed (OQ4) re-derived `slot → node` bindings from the live ON_DUTY snapshot on
*every* leader activation. On a leader change it could bind the durable slots to CTM-provisioned
*replacements* and leave the live *original* nodes unbound — turning the live majority into "surplus."
Combined with surplus reaping, this collapsed cluster-B (a leader reaped four live seeds at once,
dropping below quorum → cascade self-drain). Root cause: **the node↔slot binding was not stable
across leader change.** This redesign makes the binding durable and authoritative in KV.

## 1. Invariant

- Exactly `clusterSize` durable integer slots `0..S-1` exist in KV. `#slots = desired size`.
- **Every slot is always bound** to a node id — either a live occupant or an in-flight-provisioning
  occupant. There is no "empty but unowned" slot in steady state (formation is the one gated
  exception, §3).
- **KV is the single source of truth.** A node's membership is defined solely by whether it holds a
  slot binding. **A core node with no binding is an orphan.**
- Scaling = changing the slot count. Everything else converges from the invariant automatically.

## 2. Binding is created once, then preserved

`seedOrReseedSlots` (called from `activate()` on leader gain) becomes **create-once / preserve**:

- **KV has no slots (first formation):** leader creates the slot set and binds the present bootstrap
  nodes (§3).
- **KV already has slots (leader change / re-activation):** **do NOT wipe, do NOT rebind.** Read the
  existing bindings and `reconcile()`. Live nodes keep their slots across leader change. If
  `configured` changed (scale), `maintainSlotSetSize` adds/removes slots — still no rebind.

This single change eliminates the orphan/collapse class: killing a node frees *its* slot (refilled in
place); the other nodes keep theirs.

## 3. First formation (the gated exception)

Before a leader exists there are no slot atoms and no authority to act — a bootstrapping node has
nothing that could tell it "you have no slot, stop." It simply KV-watches. So **there is no
premature-drain race at formation.**

The leader is elected only after quorum forms. On first activation (KV empty) it creates the slot set
and binds present nodes. A bootstrap node that joins *after* the initial binding and holds no slot
**binds into an empty slot if one exists** rather than being drained. Provisioning into a still-empty
slot sits behind the provisioning **stability window** (~30s), so the remaining bootstrap nodes join
and bind before any replacement is provisioned. The orphan self-drain predicate (§5) does not fire
during formation because it requires the slot set to be at full `configured` size.

## 4. Binding at provision time (slot is bound from t=0)

When provisioning is triggered, the slot is bound to the new occupant **immediately** — no
"empty FILLING" window:

- **CTM mints the NodeId** (KSUID, via the shared `IdGenerator`) and writes the slot binding
  (`assignedNodeId = some(mintedId)`) *before* invoking the provider. The provider names the
  container/VM **verbatim** with that id. (Reverses the recent provider-owns-identity choice; safe
  because the prior ghost-`JOINING` bug was a guessed-*ordinal* mismatch, not pre-generation — a real
  KSUID used verbatim by the provider produces no mismatch. No extra `source-id` NodeId component.)
- This is the reserve-then-provision flow (already implemented) tightened so the id is present from
  the first write. A provisioned node therefore finds its binding already committed when it joins, so
  it is never a false orphan.

### Reprovision backoff (breaks the timeout-too-short loop)

The only residual reprovision case: `provisioning_timeout < real_latency` → the slot times out and is
rebound to a successor while the first occupant joins late into a slot it no longer owns (→ it
self-drains as a wasted spawn). Handled **per-slot, in the slot atom**, with no leader↔orphan channel:

- Slot carries a **reprovision counter**. Each reprovision increments it **and doubles the timeout**.
- A cleanly-joined occupant (reached healthy within timeout) **resets** the counter; the leader
  **learns** the effective base timeout from the value that finally worked (clamped — a single cold
  outlier must not set a huge cluster-wide base).
- **Cap + escalation:** doubling stops at a ceiling / after N reprovisions; the slot is marked
  `UNFILLABLE` and an alert is raised. This distinguishes *slow* provisioning (backoff fixes it) from
  *broken* provisioning (bad image / crash-loop / wrong secret — backoff would otherwise mask it
  forever while the cluster silently runs short).

Accepted cost: a late-but-healthy first occupant is drained as a wasted spawn before the timeout
widens — bounded (logarithmic), self-correcting.

## 5. Orphan self-drain (self-policing, leaderless)

A core node removes *itself* when it is a genuine orphan — no leader-side en-masse reaping. The
predicate fires only when the node's KV view is provably converged:

```
if (core
    && rabia.isActive()            // sync complete; NOT Syncing/Paused/Stopped
    && inQuorum()                  // connected to a true majority → synced == converged
    && graceElapsed                // dwell after join/activation
    && slotSet.size() == configured // never act on a partial set
    && !slotSet.contains(self)) {
    selfDrain();                   // Runtime.halt via SelfDrainCoordinator
}
```

**Why this is the converged-read:** Rabia sync transfers a whole-state snapshot from a responding
quorum, choosing the highest `lastCommittedPhase` (`RabiaEngine.java:749-787`). By quorum
intersection, a synced node holds the latest committed state — **synced == converged**. `isActive()`
is false during the `Syncing`/`Paused`/`Stopped` buffering windows where the local view is stale, so
gating on it excludes those. The one edge — `syncQuorumSize` is computed off `connected`, not
`clusterSize`, so a *degraded-connectivity* sync could return a slightly stale snapshot — is closed by
the `inQuorum()` conjunct: a node that cannot reach a true majority fails `inQuorum()` and defers to
the quorum-loss trigger instead of making an orphan decision. No new consensus accessor is required.

**Quorum-safe by construction:** if every slot is always bound (§1, §4), a node with no binding is
genuinely beyond the `clusterSize` slots, so draining it can never remove a quorum member. This is the
property the leader-side reaper lacked. Apply-lag is benign in this direction: a node's own binding is
written at provision time (§4), before it boots, so a synced node always sees its own binding when it
legitimately has one; it reads itself absent only when genuinely unbound.

## 6. No leader-side reaper

There is **no leader-side surplus-occupant reaper.** The self-drain of §5 is the *sole* orphan-removal
mechanism. The leader manages **slots** only — create/preserve (§2), bind-at-provision (§4), free a
confirmed-dead occupant's slot for refill (D2), and remove surplus slots on scale-down. It **never
directly terminates a live node.**

- **Scale-down:** the leader removes the surplus slot atoms `[configured..S-1]` from KV (unbinding
  their occupants). Those now-unbound nodes self-drain via §5. The leader does not drain them itself.
- **Dead occupant:** the leader frees the slot (unbind) and best-effort cloud-reaps the already-dead
  container; the slot refills (§4). This is housekeeping of a confirmed-dead node, not live-node
  termination.

Rationale (maintainer decision): keep it simple, and avoid two orphan-removal actors. A leader-side
reaper running in parallel with node self-drain is a second mechanism that *interferes* with the first
and is hard to nail down when it misbehaves — the c525c9116 reaper is precisely what reaped four live
nodes at once and collapsed cluster-B. One actor (the node, deciding its own fate) is safer.

## 7. Risks → mitigations (carried from the design review)

| Risk | Mitigation |
|---|---|
| Stale/partial KV read → correct node false-orphans | `isActive() && inQuorum() && set==configured && grace` (§5) |
| Reprovision loop (timeout < latency) | per-slot backoff + counter reset on join + learned base (§4) |
| Backoff masks a genuinely unfillable slot | cap + `UNFILLABLE` + alert (§4) |
| Identity reversal re-opens ghost JOINING | CTM mints real KSUID; provider honors verbatim (§4) |
| Worker bleed-through (always "not in core set") | strict core-role gate on every predicate (Scope) |
| Binding bookkeeping bug becomes lethal | binding writes few, centralized in CTM, heavily tested |
| Two orphan-removal actors interfere | **only one actor** — the node self-drains; no leader-side reaper (§6) |
| Two node-side self-drain triggers (orphan vs quorum-loss) | mutually exclusive by guard: orphan requires `inQuorum()`, quorum-loss requires `!inQuorum()`; both in `SelfDrainCoordinator` |

## 8. Code seams

- `ClusterTopologyManagerRecord.seedOrReseedSlots` → create-once / preserve; drop `wipeAllSlotAtoms`
  on the reseed path. (§2)
- Provisioning path → CTM mints KSUID, binds slot with `assignedNodeId` from the first write; provider
  honors the id. (§4)
- `ProvisioningSlotValue` → add reprovision counter + per-slot effective timeout; cap/UNFILLABLE
  state. (§4)
- `SelfDrainCoordinator` (+ a core-only periodic check) → orphan self-drain predicate of §5, alongside
  the existing quorum-loss trigger.
- Reaper (`reapStableSurplusOccupants` / `reapReseedSurplus` / `reapSurplusOrphanOccupancyAware`) →
  **deleted**, along with the surplus short-drain machinery (removes the c525c9116 + 8298fccae
  occupant-reaping). Scale-down `removeSurplusSlots` removes only the slot atoms `[configured..S-1]`;
  their occupants self-drain via §5. No leader-side occupant termination. (§6)

## 9. Validation

Docker suite-02 is the arbiter (the in-process harness is synchronous and cannot reproduce these
async/cross-plane behaviors). Decisive checks: converge to exactly 5 after the kill chain; **no
collapse**; `Auto-heal restores to 5`; `test-kill-multiple` (kill 2 → survive); `test-self-drain-
quorum-loss` S19 (kill 3 → survivors exit code 2) + S20 (restart → recover). #231 failure-detection
latency (S01 budget, NODE_FAILED-within-90s) remains a separate track.
