# Membership-FSM Unification Spec (per-node authority + transport executor)

> ✅ **ADOPTED and EXTENDED by [`cluster-topology-overhaul-spec.md`](cluster-topology-overhaul-spec.md)** (Waves 5/7 complete the executor cutover and the broadcast-set fix; Wave 2 implements the §6 `QuorumLossDetector → role=core` direction; Wave 9 retires the vestigial transport ACTIVE/PASSIVE role). The LIVE Phase-2 executor-design rationale.

**Status:** DRAFT for review · **Depends on:** `membership-convergence-fsm.md` (the FSM model), `membership-architecture-v2-spec.md` (derive-from-reality direction) · **Sibling:** GitHub #241 (community topology) · **Motivating bug:** the consensus dead-ULID retry-storm (#68 quiesce root)

## 1. Why

The 2026-06-07 cutover made the per-member `MembershipFsm` the authority the **reconciler counts**. But a deeper investigation of #68 found a **third membership authority** still live: the QUIC transport's own peer table. `QuicClusterNetwork.broadcastPayload` iterates `peers.values()` (`QuicClusterNetwork.java:910`) — the transport's *own* resident set, governed by its *own* phase machine (INIT/CONNECTING/CONNECTED/EVICTED/REMOVED) and its *own* lifecycle logic (SWIM-allows readmit, `departurePermanent`, anti-resurrection). When that set diverges from membership, consensus broadcasts to a dead-but-still-CONNECTED ULID forever (`Retry N/200 @25ms`, never escalating) → the broadcast target keeps a live node perpetually SUSPECTED → `ClusterGenerationProjector.deriveClusterQuiescence:334` (DEGRADED on `hasSuspected`) never quiesces → #68.

This is the **same multi-authority class** the cutover fixed for the reconciler — one layer down. The structural cure: make the per-node FSM the **single authority** for membership *and* for what the transport connects to. The transport becomes a **dumb executor** that reconciles its connections to the FSM's desired set. SWIM feeds the FSM exclusively; every other consumer becomes an FSM projection; NTT is demoted to a pure debounce sensor (`PresenceSampler`).

## 2. Settled design decisions (from the Q&A)

1. **Full-visibility tracking, bounded connectivity.** Every node's FSM tracks *every* node (cheap: a record + driver per node). The FSM *drives connections* only for the node's bounded connection set. **Confirmed feasible: SWIM is already cluster-wide** (`SwimProtocol.java:328,696,747`), so full visibility costs nothing new.
2. **Self-described SWIM descriptor `(NodeId, incarnation, address, role, source)`.** `role` (CORE/WORKER) + `source` are immutable assembly/provision-time facts; `zone` is a sub-property of `source` (`SourceProfile.java:19`); **community is derived downstream** (#241), never a descriptor field.
3. **Transport = dumb executor (Option A), level-triggered.** The FSM publishes a **desired connection-set**; the executor continuously reconciles actual→desired. Terminal disconnect happens only when a peer leaves the desired set, which happens only on **co-confirmed DEAD** (SUSPECT keeps the peer in-set — the cluster-to-zero guardrail). Anti-resurrection/incarnation fencing lives in the FSM; the executor never independently readmits.
4. **Single per-node authority; only scaling leader-gated.** The FSM is always-on per node (tracking + transport-driving). `LeaderReconciler` (provision/drain) is the sole leader-gated consumer. All other consumers become FSM projections. **NTT → `PresenceSampler`** (pure SWIM-sampling debounce clock feeding FSM edges).

## 3. Two state machines (the clean seam)

The redesign separates two lifecycles that the current code conflates:

| | **Membership FSM** (authority) | **Connection executor** (mechanical) |
|---|---|---|
| States | OBSERVED → MEMBER → SUSPECT → DEPARTING → DEAD | INIT → CONNECTING → CONNECTED → EVICTED → (drop) |
| Owns | who is a member, role/source, incarnation fencing, the **desired connection-set** | dial, single-dialer direction, offline-buffer, reconnect-backoff, per-lane streams, write |
| Driven by | SWIM observations + PresenceSampler debounce edges + executor connect/disconnect feedback | the FSM's desired connection-set (reconcile actual→desired) |
| Per-node? | yes, always-on | yes |

The FSM answers *who*; the executor answers *how*. The executor's phase machine is **mechanical delivery state**, not a membership authority — so it can never diverge into a third authority, because it has no opinion of its own; it only chases the FSM's desired set.

## 4. The transport executor (Option A, level-triggered)

### Interface
```
// FSM → executor (level-triggered desired state)
Set<PeerTarget> desiredConnections()            // PeerTarget = (NodeId, resolvedAddress)

// executor reconciles continuously:
//   target in desired,  not connected   → dial (single-dialer gated)
//   target in desired,  connected        → keep
//   connected,          not in desired    → tear down
// + mechanical: single-dialer (ConnectionDirection.shouldInitiate), offline-buffer,
//   reconnect-backoff, per-lane streams, write, unresolved-address safe-fail

// executor → FSM (feedback, drives FSM transitions)
onPeerConnected(NodeId)        // inbound hello accepted / outbound established
onPeerDisconnected(NodeId)     // connection dropped (liveness signal, NOT terminal)
```
**Broadcast** changes from iterating `peers.values()` to iterating the **connected subset of the FSM's desired set** — so a non-member can never be a broadcast target. That single change dissolves the storm.

### What the executor KEEPS (mechanical — `QuicClusterClient`/`Server`/`QuicPeerConnection`)
Dial + bind, single-dialer direction (`ConnectionDirection.shouldInitiate`, `:31`), Hello handshake, per-lane stream open/register, write + CONSENSUS backpressure retry, offline-buffer (transient delivery), reconnect-backoff timing, stale-channel teardown, unresolved-address safe-fail.

### What MOVES to the FSM (decision)
The **desired-set membership** of each peer (replaces the transport's independent EVICTED-vs-REMOVED decision, `swimMembershipAllows` readmit gate, protection-window, and the missing-peer reconciler's "should this peer exist" judgment). Incarnation-fenced rejoin (`PeerState.readmit` → `MembershipState` rejoin cell, already present via `rejoinIfNewer`).

### What gets DELETED from `QuicClusterNetwork`
`peers`-as-authority for broadcast; `considerPeerForReconcile`/`reconcileMissingPeersTick` membership judgment; `swimMembershipAllows`/`swimHealthAllows` gates; `departurePermanent` independent REMOVED decision; the readmit block in `onPeerConnected`. (The *mechanical* reconnect loop survives, now driven by the FSM's desired set.)

### Guardrail (avoids the `isActive()`-to-zero history)
A peer leaves the desired set **only on co-confirmed DEAD** (SWIM-FAULTY ∧ liveness-gone, debounced). SUSPECT keeps it in-set → connection held → transient-tolerance preserved. The original cluster-to-zero came from a *naive* `isActive()` evict on transients; here the desired-set membership is debounced + co-confirmed, so the executor never tears down a blip.

## 5. SWIM descriptor + wiring

**SWIM is cluster-wide** — full visibility is free. Gaps to close:

1. **`MembershipUpdate` carries only `(NodeId, state, incarnation, address)`** (`SwimMessage.java:63`). Gossip-learned peers (`MemberDiscovered`/`dialInfoFor`, `SwimProtocol.java:926,932`) get **address-only** NodeInfo. **CARRIER DECISION (Wave A finding):** the `@Codec` is **purely positional with no field framing** — appending Optional fields to `MembershipUpdate` would desync old↔new gossip (a new node would read 2 extra "fields" from the next list element). So role/source ride the existing **`Announce.nodeInfo.labels`** instead (Announce already carries full `NodeInfo`): store labels on `SwimMember` from `Announce`, hydrate them in `dialInfoFor`. No wire change, fully back-compat. **Gap (acceptable):** a peer learned ONLY via transitive `MembershipUpdate` piggyback (never heard its `Announce`) lacks role/source — fine for an all-core cluster (every core hears every core's Announce, the storm-fix case); transitive propagation for mixed/worker clusters is a **#241 follow-up** needing a versioned/length-prefixed carrier.
2. **`AETHER_SOURCE` self-awareness** — add to `ClusterIdentityEnv.IDENTITY_VARS` (`:26`), add `NodeInfo.LABEL_SOURCE`, read in `Main.collectNodeLabels()` (`:519`, mirroring `AETHER_ZONE` `:522`); provisioning auto-propagates via the existing `IDENTITY_VARS` loop (`UserDataTemplate`, `DockerComputeProvider`).
3. **`AETHER_ROLE` self-awareness (NEW gap)** — a node currently hardcodes `NodeRole.ACTIVE` (`Main.java:436`) and does not self-know its CORE/WORKER role. Add `AETHER_ROLE` the same way (env → label → descriptor). Source config already declares roles (`SourceProfile.roles`), so provisioning knows it at mint.
4. **Peer dial address** comes from SWIM `resolvedAddress()` (`connectPeer`, `QuicClusterNetwork.java:723`) — available; the FSM's `PeerTarget` carries it.

## 6. Consumer migration

| Consumer | Reads today | Becomes | Risk |
|---|---|---|---|
| `LeaderReconciler` | `FSM.countedMembers()` | no change (done in cutover) | none |
| `QuorumLossDetector` | `NTT.currentMemberCount()` (`AetherNode:520`) | `FSM` count, **role=core, MEMBER-only (strict)** | low |
| forward-routing `keepOnlyAccessible` | `NTT.lastEmitted` (`NTT:501`; callers `HttpForwarder:267/509/540`, `AetherNode:1421`) | `FSM.reachableMembers()` | low |
| DHT `filterByLiveness` | `network.livePeers()` (`DistributedDHTClient:218`) | `FSM.countedMembers` supplier | low |
| quiesce `deriveClusterQuiescence` | `swimHints` snapshot (`ClusterGenerationProjector:202,334`) | `FSM.memberStates()` → HealthHint | medium (state→hint map; **directly gates #68** — validate against QUIESCED) |
| governor election / `GroupMembershipTracker` | `SwimMember.state()==ALIVE` | FSM projection (role-scoped) | **deferred to #241** |
| broadcast target set | `peers.values()` (`QuicClusterNetwork:910`) | connected subset of FSM desired set | **the storm fix** |

## 7. `PresenceSampler` (NTT demotion)

NTT splits: **KEEP** (→ `PresenceSampler`, next to the FSM package) the SWIM periodic sampling, up/down hysteresis streaks, QUIC/SWIM transient bias, and the **emit debounced edge to FSM** (`onDownHysteresisMet`, already wired). **SHED** (→ FSM projections) `stableMembers`-as-authority, `evict`, `peakMembershipCount`, `keepOnlyAccessible`, `currentMembers`/`currentMemberCount`. The `PresenceSampler` decides nothing; it is the FSM's debounce clock.

**FSM always-on changes** (`MembershipFsm` + `AetherNode`): delete the `active` gate / `activate`/`deactivate`/`members.clear` (`MembershipFsm:137,160,361`) and `toggleMembershipFsmOnLeaderChange` (`AetherNode:2581,1744`); ingress becomes unconditional; per-member tracking lazy-inits on first edge. Incarnation high-water (`MembershipContext`) already guards rejoin across the (now-absent) leader-change clear → no data loss.

## 8. Phasing — complete in one spike, with a #241-gated seam

- **Core mesh (this spike, no #241 dep):** the connect-set for a CORE node = `{role=core members}`. On an all-core cluster that's `countedMembers()`. This fully implements FSM-drives-transport for the core mesh and **fixes the storm + #68**.
- **Worker connect-set (gated on #241):** a worker's desired set = governor + community, which needs #241's community resolution. The FSM exposes a `connectionSet(self, fullMembership)` projection seam; core fills it now (`role=core`), #241 fills the worker case. Tracked as a #241 deliverable checklist item.
- **Governor/group read-source swap → #241.**

## 9. Risks
- **Most fragile subsystem** (dual-dial / `isActive()`-to-zero history). Mitigations: level-triggered desired-set (no edge-command races); co-confirmed-DEAD-only removal (transient-tolerance preserved); single-dialer kept mechanical & unchanged; build on the spike branch, Docker-validate before merge.
- **`MembershipUpdate` codec change** — additive Optionals; verify round-trip + back-compat (no envelope bump unless the slice-processor output structure changes — it doesn't).
- **Quiesce state→hint mapping** is the #68-critical path — validate against `SUSPECT/refute → 0` and `ClusterQuiescence.QUIESCED`, not against artifact/connection counts.

## 10. Validation
- **Unit:** FSM always-on + desired-set projection + PresenceSampler debounce + each consumer projection. `LeaderReconcilerTest` already adapted (cutover).
- **Docker (the proof):** clean-slate `02`-chaos on the spike image → (a) **storm gone**: no `CONSENSUS stream backpressured` retry loop against an evicted ULID; (b) **#68**: `await_generation_quiesced` passes (SUSPECT/refute count → 0, QUIESCED); (c) no regression (formation, re-election, restore-READY). Capture per-node `docker logs` before teardown.
- **Reconcile:** spec §4-§7 → code, tag DONE/MISSING/STUB/SHORTCUT; commit only when MISSING=STUB=SHORTCUT=0.

## 11. Rename
`NodeTopologyTracker` → **`PresenceSampler`** (+ package move beside the FSM), reflecting its demoted debounce-clock role.
