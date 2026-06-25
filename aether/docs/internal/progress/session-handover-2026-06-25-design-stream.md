# Design-Stream Session Handover — 2026-06-25 (community membership + #277 postpone)

> Companion to aether-main's `session-handover-2026-06-25.md`. This is the **design-stream** side.

## ⚡ TL;DR

- **#241 community membership (ACTIVE):** 3 slices delivered — **#357 + #358 MERGED, #359 OPEN**. The
  community-formation loop is now **wired end-to-end and unit-proven, but NOT integration-proven.** Next:
  a Forge proof, then the Phase-A remainder.
- **#277 observability aspects (POSTPONED):** PR #356 on hold pending a deeper per-injection-point
  `AspectFactory` codegen design. Resume ref: `277-observability-design-state-2026-06-25.md`.
- **Coordination model:** the design-stream now *implements* features alongside an **aether-main** agent
  (in `../pragmatica`). I own assigned features end-to-end on `feat/*` branches off `release-1.0.0-rc2`;
  push to GitHub `origin`; **aether-main reviews + merges — I NEVER self-merge.** Sync via GitHub `origin`.

---

## 1. #241 Community membership — the active work

Implementing `aether/docs/specs/worker-membership-spec.md` (the spec-of-record for #241). The community/
worker tier was **designed but unbuilt**; the overhaul gate (Waves 2/4/5/7) is satisfied → unblocked.
Building risk-first, slice by slice, green reactor between each.

### The formation loop (what slices 1–3 build)

```
worker joins
  → leader mints its community + assigns it          [slice 1]  ClusterDeploymentState.assignWorkerRole
  → worker self-elects governor + announces members  [slice 3]  GovernorAnnouncer ← SWIM observations
  → leader FSM flips community FORMING→ACTIVE         [slice 2]  ClusterDeploymentState.evaluateCommunityStates
  → planner places slices on ACTIVE communities       [slice 1/2] CommunityPlacementPlanner
```

### Slices done

| Slice | PR | What |
|---|---|---|
| **1** | **#357 MERGED** | Committed `CommunityId` substrate. `CommunityKey(communityId)` / `CommunityValue{sourceName,role,targetSize,state,createdAt,dissolvedAt}` / `CommunityState{FORMING,ACTIVE,DEGRADED,DISSOLVING,DISSOLVED}` (slice module); `ActivationDirectiveValue` extended → `(role, communityId, governorHint)` + serializer round-trip fixed; **leader mint** at `ClusterDeploymentState.assignWorkerRole` — deterministic `<source>-w-0`, atomic `CommunityKey`+directive batch, race-safe by determinism; planner reads committed `CommunityKey`. |
| **2** | **#358 MERGED** | **Per-community FSM**, leader-evaluated, edge-driven, in `reconcile()` → `evaluateCommunityStates`. `nextCommunityState` pure switch: FORMING→ACTIVE (liveMembers ≥ RF=3), ACTIVE→DEGRADED (< RF), DEGRADED→ACTIVE (≥ RF). `liveMembers` = `GovernorAnnouncementValue.memberCount()`. `CommunityValue.withState`; planner tightened to **strict ACTIVE**. DISSOLVING/DISSOLVED deferred to Phase C. |
| **3** | **#359 OPEN** | **Closed the loop.** `AetherNode`: worker reads committed `communityId` from its directive → `activateWorkerMode(communityId, swimHealthDetector)`; emergent `myGroup().communityId()` suppliers replaced with `() -> communityId`; **announcer re-pointed to `forwardingClusterNode`** (worker is observation-only — raw `clusterNode.apply` is a dropped local apply); SWIM observation listener → `GovernorAnnouncer.onMembershipChange(communityAliveMembers)`. New `CommunityMembershipFilter` (committed-directive-scoped, NOT SWIM source-labels — labels are empty for gossip-learned members), `CoreSwimHealthDetector.aliveMembers()`. `swimHealthDetector` reaches the later-constructed announcer via an `AtomicReference` holder. |

### Status — honest

**WIRED + UNIT-PROVEN, not integration-proven.** Each slice is green (full reactor) with unit tests (mint
once+reuse via a recording consensus→KV loop; every FSM edge + edge-driven no-write; governor self-elect +
community-filtered announce + cross-community exclusion). **There is no running-cluster evidence yet** that a
real worker join drives a `CommunityKey` to ACTIVE. Do not claim the loop "works" until the Forge proof exists.

### Scope / form (what this is, and isn't, yet)

- **Single-community-per-source.** `communityId = <source>-w-0`; source ≡ community. `targetSize=100` is a
  placeholder constant.
- **Deferred:** growth comparator (multi-community per source, real `targetSize`, scale/drain → Phase C);
  DISSOLVING/DISSOLVED transitions (Phase C); `MembershipFsm` connection-set registration (§3.5 transport seam);
  full governor read-source swap to the FSM projection (§3.4 — currently the SWIM-observation feed is the
  functional equivalent); `GroupAssignment` deletion (a later slice); announcement-TTL liveness + spokesman
  retirement (D4); `NodeInfo` lazy label pull (D9); DHT wiring (Phase D).

### Next steps (in order)

1. **Forge integration proof** — *N (≥3) workers join one source → assert the `CommunityKey` reaches ACTIVE.*
   The honest-evidence step + the **first exercise of the §4 scaling topology**. No community Forge scenario
   exists yet (forge-tests has only membership/provisioning probes) — build one. **Run after #359 merges.**
   (Tracked as task; this is the recommended next action.)
2. **Phase-A remainder:** announcement-TTL liveness + spokesman retirement (D4) · governor read-source full
   swap (§3.4) · `NodeInfo` lazy label pull (D9) · `GroupAssignment` deletion.
3. **Phase B** (scoped watch/pull KV, D5) → **C** (growth/scale/drain) → **D** (DHT) per spec §10.

### Key files / anchors

- **Schema (slice module):** `aether/slice/.../kvstore/{AetherKey,AetherValue,CommunityState,KVStoreSerializer}.java`.
- **Leader (aether-deployment):** `cluster/fsm/ClusterDeploymentState.java` — `assignWorkerRole`,
  `evaluateCommunityStates`/`nextCommunityState`/`communityLiveMembers` (~:1612), `reconcile` (~:1598);
  `cluster/fsm/CommunityPlacementPlanner.java`; `cluster/fsm/ClusterDeploymentContext.java` (`memberSource`
  read-seam); `cluster/ClusterDeploymentManager.java`.
- **Worker (node):** `node/AetherNode.java` — `handleActivationDirective` (~:3595), `activateWorkerMode`
  (~:3631), the `swimHealthDetectorHolder` (~:1938/2015); `worker/governor/{GovernorAnnouncer,GovernorElection,
  CommunityMembershipFilter}.java`; `node/health/CoreSwimHealthDetector.java` (`aliveMembers`).
- **Spec:** `aether/docs/specs/worker-membership-spec.md` (§3.3 FSM · §3.4 governor · §3.5 connectionSet · §4.1
  join flow · §10 phases). Ticket **#241**.

### Codebase gotchas (verified, save re-discovery)

- `Fn1<R,T1>` is **return-first** (`R apply(T1)`).
- `@MessageReceiver` is `@Retention(SOURCE)` / doc-only — KV dispatch is the `KVNotificationRouter` chain.
- Sealed `AetherKey`/`AetherValue` use implicit permits, but a new type must be added to the **3
  `KVStoreSerializer` switches** (the String-section one is NOT compile-checked).
- `CommunityValue.communityValue(...)` is the FORMING-mint factory.
- `MemberDescriptor.source` carries the SWIM source label; **`SwimMember.labels()` is empty for
  gossip/piggyback-learned members** → scope by committed directive, not labels.
- A **worker is observation-only** (`authorizeObservation`) → consensus writes must go through
  `ForwardingClusterNode`, not the raw `RabiaNode`.
- `ClusterDeploymentState` (CDM) FSM is **leader-only** (`Active` on leader; `deactivated` guards).

---

## 2. #277 Observability aspects — POSTPONED

PR **#356** (per-slice foundation) is **on hold — do not merge.** Review (aether-main + repo owner) steered
the granularity to **per-injection-point** via an **`AspectFactory`** in the generated factory param-0 (no
envelope bump — GA format = 1000; memory verified negligible). The remaining open work is the **codegen
design** (always-generate-the-wrapper + the per-method weave). Reusable PR1 parts (config snapshot, aspect,
registry/key/value/serializer, lifecycle, the load/put race fix) carry over.

**Resume ref:** `aether/docs/internal/progress/277-observability-design-state-2026-06-25.md` (full state,
decisions, open questions) + the #277 GitHub comment.

---

## 3. How to pick up

1. **First:** check if #359 merged. If yes, sync `release-1.0.0-rc2`, branch fresh for the Forge proof.
2. **Do:** the Forge community-formation probe (§1 next-step 1) — the highest-value, mission-aligned action
   (it converts "wired" into "proven" and opens the §4 scaling work).
3. Then continue Phase-A remainder, or pick the next thing the user directs.
4. **Always:** `feat/*` branch off release, PR to origin, **aether-main reviews + merges — never self-merge.**
   Heads-up aether-main before editing shared deployment-FSM files.

*Memory file `project_design_stream_now_implements.md` carries the same state in condensed form.*
