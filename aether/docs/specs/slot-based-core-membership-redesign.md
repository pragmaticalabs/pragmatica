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
- **Coherence (keystone): for a core node, `ON_DUTY ⟺ holds a slot`.** Binding DRIVES the lifecycle
  (bind → ON_DUTY; no/lost slot → drain). The two must never disagree — slice routing keys off
  lifecycle-`ON_DUTY` (`AllocationPool`) while membership keys off slots; if a node could be ON_DUTY
  without a slot, the model leaks. "Bound" and "ON_DUTY/serving" are the same predicate for core.
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

## 3. Universal slot-fill algorithm (subsumes formation, re-provision, late-join)

A single rule fills any empty slot — at formation and in steady state alike. For each empty slot, in
deterministic order:

1. **Bind an existing node if one is available:** pick a **connected, joined, unbound, non-draining**
   core node (deterministic: sort candidates × sort empty slots) and bind it via a conditional write
   (CAS: only if the slot is still empty AND the node still unbound). Fast — no boot latency. This is
   how the bootstrap seeds acquire slots at formation (they are all connected-joined-unbound) and how
   a spare/late/descaled-but-not-yet-drained node is reused.
2. **Else provision a new node** via reserve-then-provision (§4) — the FALLBACK, only when no existing
   candidate exists.

**Provisioning is suppressed during formation.** Until the cluster has formed (phase `NORMAL`), step 2
does not run — empty slots are filled only from existing connected nodes (step 1). Exit condition:
all slots filled, OR a formation-timeout (so a slot whose expected bootstrap node never appears is
eventually provisioned). Before a leader exists there is no authority to provision or drain anyway, so
a bootstrapping node just KV-watches (no premature-drain race).

This is universal because it treats core nodes as fungible slot occupants regardless of provenance
(consistent with the deleted provenance shield). Consequences that fall out for free: more connected
nodes than slots → the leftovers get no slot → drain (§5); scale-down → slot count shrinks → the
now-slotless occupants drain — one mechanism. **The FSM must transition an *existing* connected node to
occupant on `SlotClaimed`** (not only a freshly-provisioned one) — verify the `(state, SlotClaimed)`
cells actually land it ON_DUTY rather than nop.

Caveats (low-probability, flagged in the design review): a slot bound to a dead-but-undetected
occupant reads as filled, so reuse waits on failure-detection freeing it (inherits #231); a flapping
node needs a "connected-stable-for-N" guard before it is bindable; if slots ever carry placement
constraints, "any connected node" is wrong (the current core is homogeneous).

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
decision is **state-based, not a fixed-time grace**: a node defers as long as there is *any* slot it
could still be placed in, and drains only once every slot is occupied by a *connected* member and it
is not one of them.

```
liveFilled = count of slots whose occupant is in the connected-members set   // dead/disconnected occupant ⇒ slot is NOT live-filled
if (core
    && rabia.isActive()            // sync complete; NOT Syncing/Paused/Stopped
    && inQuorum()                  // connected to a true majority → synced == converged
    && liveFilled == configured    // every slot live-filled ⇒ converged, no room for me; while < configured, WAIT
    && !boundToConnectedSlot(self)) {
    selfDrain();                   // Runtime.halt via SelfDrainCoordinator
}
```

"Filled" means the occupant is in the node's **connected-members view**, not merely that the slot
atom has an `assignedNodeId`. This makes the rule wait through a **dead-occupant slot** (occupant gone
but slot not yet freed): such a slot is *not* live-filled, so `liveFilled < configured` → the node
keeps waiting (it could be rebound into that slot once §3/freeDeadSlots clears it) instead of falsely
concluding "all full → I'm surplus." This replaces the fixed `grace` + `slotSet.size()==configured`
guard with a dynamic converging-vs-converged signal, and it subsumes the systemic-binding-sanity
backstop: if the slots were bound to nodes that aren't even connected, they read as not-live-filled →
the node waits rather than drains.

**Why the gate is the converged-read:** Rabia sync transfers a whole-state snapshot from a responding
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
