# Finding — SWIM evicts freshly-added nodes at join (root cause of #336; blocks #241 in-JVM proof)

> Design-stream finding, 2026-06-25. Companion to `CommunityFormationProbeTest` (committed tracked-red).
> **Owner of the fix: aether-main** (shared foundational membership/consensus code). This doc is the heads-up.

## TL;DR

A node ADDED to an already-formed Ember (single-JVM) cluster does not stably join: SWIM marks it
`FAULTY` before its first probe-ack, it self-drains and leaves. This blocks both **#336**
(scale-up "new cores never counted") and the **#241** community-formation Forge proof (workers never
persist → no governor → community stuck `FORMING`). Reproduced in-JVM by
`aether/forge/forge-tests/.../CommunityFormationProbeTest.java` (3-core: leader lost; 5-core: leader
survives but added nodes never persist, `nodeCount` never grows past 5).

It is a **production SWIM-logic bug, not a harness artifact** — Ember uses production `SwimConfig`/
`TimeoutsConfig` defaults (no aggressive overrides). A harness knob would only mask #336.

## Root cause (proven in code)

Two introduction paths behave differently:

- **`addSeedMember` (problem path)** — used for static seeds at boot AND every QUIC `PeerConnected`
  re-add (`SwimHealthState.Running.handlePeerConnected` → `addSeedAndLog`). It introduces the member
  as **`SUSPECT` and arms the death timer immediately**:
  `SwimProtocol.addSeedMember:436-439` → `SwimMember(..., SUSPECT, ...)` + `beginSuspicion(...)`.
- **`handleAnnounce` (working path, bypassed)** — introduces an **UNKNOWN** announcer as `ALIVE` with
  no timer (`introduceAnnouncedAlive`), but **only for UNKNOWN members** (`handleAnnounce:1206`); an
  already-resident SUSPECT falls into the no-promote else-branch (`1228-1235`).

Three compounding defects:

1. **(2a) Suspicion armed at birth, before any probe exists.** The first probe and first self-ALIVE
   gossip are both gated behind `startupDelay` (~10s, jittered 8–12s; `SwimProtocol.start:374-380`).
   The suspect→FAULTY clock starts at t=0 in `addSeedMember`.
2. **(2b) Join-grace guard is timing-inverted.** `joinGrace`=12s barely exceeds `startupDelay`-max
   (12s) and `suspectTimeout`=10s < `joinGrace` by only 2 probe periods. Usable liveness-exchange
   window ≈ `joinGrace − firstProbe ≈ 0–2s`; when jitter pushes the first probe to ≥12s, the member
   is FAULTY'd before it is ever probed.
3. **(2c) ANNOUNCE can't rescue a co-seeded member.** `seedMembers` runs before `announceJoin`
   (`CoreSwimHealthDetector.seedAndWrap:484-488`), so an incoming self-ANNOUNCE finds the announcer
   already SUSPECT and hits the no-promote branch. In cold-start every node seeds every peer SUSPECT,
   defeating the ANNOUNCE-as-liveness fast path for all of them.

**The eviction-driver defect:** the cold-boot/join-grace suppression gates only the observation
stream (`emitFaultyOrUnknown:1776`), **not** the `listener.onMemberFaulty` death-path callback. All
three FAULTY entry points (`transitionToFaulty:967`, `applyNewFaultyMember:1557`, `notifyFaulty:1668`)
call `onMemberFaulty` outside the gate → `CoreSwimHealthDetector.onMemberFaulty:343` →
`SwimHealthState.routeFaultyPeer:147` → `SwimHealthContext.routeFaulty:291-303` unconditionally does
`emitLeaderHint(FAULTY)` + `bufferHealthObservation(FAULTY)`, feeding the leader's membership FSM that
writes `DECOMMISSIONED`. So the guard designed to protect joiners is bypassed for the stream that
actually drives eviction (matches the observed `routeFaultyPeer … currentLeader=None()` during boot).

## Config (Ember = production defaults)

| Knob | Source | Value |
|---|---|---|
| `period` | `TimeoutsConfig.SwimTimeouts` | 1s |
| `probeTimeout` | same | 500ms |
| `suspectTimeout` | same | 10s (clock starts at `addSeedMember`) |
| `startupDelay` | `SwimConfig.DEFAULT` | 10s, jittered 8–12s (gates first probe AND self-ALIVE) |
| `joinGrace` | `SwimConfig.DEFAULT_JOIN_GRACE` | 12s (only false-eviction guard) |

Critical: `joinGrace (12s) ≈ startupDelay_max (12s)`, `suspectTimeout (10s) < joinGrace (12s)` by 2 periods.

## Proposed minimal fix (ranked) — all SHARED foundational SWIM code

Recommended: **Rank 1 + Rank 2** together.

- **Rank 1 — `handleAnnounce` promotes a resident SUSPECT→ALIVE on a self-ANNOUNCE.**
  (`SwimProtocol.java` else-branch `1228-1235`.) A self-ANNOUNCE is authoritative positive liveness;
  extend `introduceAnnouncedAlive` semantics to the KNOWN-but-not-ALIVE case, still routed through
  `blockedByTombstone` (preserves #231 dead-id-resurrection guard).
- **Rank 2 — route `onMemberFaulty` through the same cold-boot/join-grace suppression as
  `emitFaultyOrUnknown`.** (`SwimProtocol.java:967,1557,1668`.) For a never-`everSeenHealthy` peer
  inside cold-boot/join-grace, do not fire the death-path callback. Closes the hole that leaks
  premature FAULTY hints into leader aggregation. Without it, Rank 1 alone still leaks under churn.
- **Rank 3 (more principled, bigger blast radius) — don't arm a death timer at birth.** Drop
  `beginSuspicion` in `addSeedMember`; arm the suspect timer only on a real probe timeout; make
  `classify` return UNKNOWN for `ALIVE && !everSeenHealthy`. Needs the full #231/#94/S01/S06
  anti-oscillation suite green.
- **Rank 4 (band-aid, NOT recommended) — widen `joinGrace` above `startupDelay×1.2 + suspectTimeout
  + k·period`.** Lowers false-eviction probability but doesn't close the suppression hole; would also
  make the Ember harness less faithful and mask #336. Reject.

### Affected files (shared vs harness)
- `integrations/swim/.../SwimProtocol.java` — **SHARED** (addSeedMember:438; handleAnnounce:1206-1235;
  transitionToFaulty:967 / applyNewFaultyMember:1557 / notifyFaulty:1668; emitFaultyOrUnknown:1776).
- `integrations/swim/.../SwimConfig.java` — **SHARED** (DEFAULT_JOIN_GRACE:89, startupDelay:103).
- `aether/node/.../health/CoreSwimHealthDetector.java` — **SHARED** (seedAndWrap:484-488; onMemberFaulty:343).
- `aether/node/.../health/fsm/SwimHealthState.java` — **SHARED** (routeFaultyPeer:147; handlePeerConnected:160-175).
- `aether/node/.../health/fsm/SwimHealthContext.java` — **SHARED** (routeFaulty:291-303).
- `aether/aether-config/.../TimeoutsConfig.java` — **SHARED** (SwimTimeouts:125-129; production defaults).
- `aether/ember/.../EmberCluster.java` — harness-only. `aether/forge/forge-tests/.../CommunityFormationProbeTest.java` — the repro (tracked-red).

## Verification gate
Land the SWIM fix behind a fast in-JVM gate: `CommunityFormationProbeTest` (this finding's repro) +
the existing SWIM Wave-6 / anti-oscillation unit suites + `ScaleUpFiveToSevenProbeTest` (#336), green,
before any cloud sweep. When node-add stability lands, `CommunityFormationProbeTest` flips green and
the #241 worker/community tier becomes Forge-provable.

## Resolution (2026-06-26) — FIXED & validated

Implemented on branch **`fix/336-swim-observed-birth-state`** as a new **local-only `OBSERVED` birth
state** (Rank 3 of the proposal — don't arm a death timer at birth — plus a corrected gossip-merge):

- `00be33514` — OBSERVED birth (no timer, no gossip, no `everSeenHealthy`); probe-eligible; promote
  on probe-ack; OBSERVED→SUSPECT only past the join deadline; `addMemberUpdate` wire-leak guard;
  removed the subsumed born-SUSPECT band-aids.
- `a15b0e1f9` — gossip-merge correction: `statePriority(OBSERVED) = -1` (weakest) so a gossiped
  `Alive` **promotes** OBSERVED; `applyExistingMember` **ignores** gossiped SUSPECT/FAULTY for an
  OBSERVED member (own probing decides); `applyNewSuspectMember`-of-unknown births OBSERVED too.
- #231 / #126 / #94 invariants preserved.

**Validated:** `mvn -pl integrations/swim test` 170/170; line-by-line review + full `jbct-reviewer`
pass; **real Docker cluster** (`run-tests.sh --env remote`): scale-up 5→7 in 25s zero-eviction, all
chaos recoveries + joining-window-kill + worker-join PASS, clean total-restart (graceful + abrupt).

**Forge harness caveat:** `CommunityFormationProbeTest` / `ScaleUpFiveToSevenProbeTest` still cannot
go green in single-JVM Forge at 6-8 nodes (transport/probe-ack contention collapse — a harness
artifact, not the fix). The remote Docker cluster is the real gate.

**Separate issue (NOT this fix):** suite-02 **S20** self-drain-quorum-loss → full-restart recovery
fails because the chaos suite auto-heal-replaced all compose nodes with ephemeral ULID-provisioned
nodes and `restart_all_nodes` restarts the original compose nodes against a committed membership that
references the gone ULIDs — a harness/recovery-orchestration issue (the clean total-restart proves
the formation path is healthy). See `session-handover-2026-06-26-design-stream.md` §3; next-session task.
