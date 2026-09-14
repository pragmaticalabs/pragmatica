### Fixed (2026-09-14 — #688: the leader's half of a node drain never ran; a blueprint with an instance on a node that self-drained froze until that node halted or the leader changed)
- **Finding 1, wired.** `ClusterDeploymentState.startDrainEviction` — deploy a replacement elsewhere, unload the
  original once it is ACTIVE — had one entry, the `MembershipDecision.NodeDraining` arm, and that decision has never
  been emitted in this repository's history (the `startDrainEviction` arm arrived with the aether-deployment import
  `7b79151ed`; the "no longer emitted — membership-v2 finale" note with `5e117fa51`, 2026-06-13; `git log -S` over
  `origin` and the 82 tags finds no producer — the archive tags are gone by ruling, so zero is expected). Meanwhile
  `reconcileBlueprint` SKIPS every blueprint with an instance on a draining node (`hasInstancesOnDrainingNodes`),
  deferring to that loop. So a node that reported DRAINING (`NodeReportedState.DRAINING` on its pong, the real
  signal, which already reached `MembershipFsm.onDrainAcknowledged` and the CDM's `drainingNodes()` supplier) froze
  its blueprints — see the scope bullet below for which drains that actually covers.
  Now the same pong-fan report also drives `ClusterDeploymentManager.onNodeDraining` (new receiver, dispatched as
  `ClusterDeploymentEvents.NodeDrainingReported`), composed in `AetherNode.drainReportListener` and registered on the
  fan by the single line `pongSignalFan.onDrainingReported(drainReportListener(membershipFsm, clusterDeploymentManager))`
  in `assembleNode`. That REGISTRATION is the whole production effect, and it is what is pinned
  [verified: `aether/ember/src/test/java/org/pragmatica/aether/ember/EmberDrainEvictionWiringTest.java` — a real
  three-node in-JVM cluster; a `DRAINING` pong delivered to the elected leader's own
  `metricsCollector().onClusterSyncPong` runs the leader-side loop to `completeDrain`, with nothing between the pong
  and the loop a test double. Reverting that one line to the pre-PR `membershipFsm::onDrainAcknowledged` (helper left
  in place) turns it red]. The eviction is attributed to the pong by PRODUCER, not by elapsed time: the loop's second
  entry, `resumeDrainEvictions`, logs `Resuming drain evictions for N nodes` immediately before it starts one, and no
  such line may precede the pong's. That distinction is load-bearing, not decorative — in the reverted run a reconcile
  tick did start the eviction 0.9 s inside the 10 s bound, so a purely time-bounded assertion would have passed
  against the unwired code.
  `AetherNodeDrainReportListenerTest` (aether/node) calls `drainReportListener(fsm, cdm)` itself, so it pins the
  helper's BODY — that the report reaches the CDM as well as the FSM — and by construction cannot pin its USE
  [verified: dropping `clusterDeploymentManager.onNodeDraining` from the helper reddens it]. The `NodeDraining` arm is
  kept (it routes to the same guarded entry), not deleted: it is the harness path three test suites drive.
- **Finding 2, a periodic trigger.** `resumeDrainEvictions` ran only on leader activation; it now runs on every
  reconcile tick too, restarting the loop for any node the draining set names that has no loop running. A per-node
  guard (`drainEvictionsInProgress`) makes both entries idempotent per drain episode — the pong repeats every ping
  interval and the tick every reconcile interval, and neither may double-issue a replacement
  [verified: `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/cluster/fsm/ClusterDeploymentStateDrainEvictionTest.java`
  — `reconcile_withADrainingNodeHoldingASlice_issuesTheReplacementLoad` (red at the base: the blueprint was skipped
  and nothing else ran), `drainingReport_startsTheEvictionOnce_andRepeatsDoNotDoubleIssue`,
  `drainWithdrawn_thenReported_again_startsAFreshEviction`, and the fixture control
  `legacyNodeDrainingDecision_issuesTheReplacementLoad`. Removing the tick call reddens the first and third; removing
  the guard reddens the second].
- **Round 2 — the guard leaked from the loop's other abandon path, and a re-drained node was then never evicted.**
  The loop parks 3 s in `checkReplacementAndUnload` waiting for the replacement to reach ACTIVE. Its early return
  (drain ended, or the state deactivated) did not release the guard, unlike the matching early return in
  `evictNextSliceFromNode`. A drain withdrawn while the loop was parked therefore left `drainEvictionsInProgress`
  holding the node with nothing scheduled to clear it, and `startDrainEviction`'s `add` — the code that refuses a
  second eviction — swallowed the node's entire NEXT drain episode. Both abandon paths now go through
  `abandonDrainEviction`, so the guard is held for exactly as long as a loop step is scheduled
  [verified: `drainWithdrawnWhileTheReplacementCheckIsParked_thenRedrained_startsAFreshEviction` — reverting the
  production hunk reddens it with `Expected size: 1 but was: 0`]. That test calls `reconcile()` nowhere and waits out
  `Active.onEntry`'s one-shot 2 s `deferredTopologyRecheck` before withdrawing, because `resumeDrainEvictions`'
  `retainAll(draining)` clears the guard silently and would otherwise mask the defect — which is exactly why
  `drainWithdrawn_thenReported_again_startsAFreshEviction`, which goes through `reconcile()`, stayed green with the
  defect present. **Precise guarantee: a drain episode's guard is released at `completeDrain`, at either abandon
  path, on `NodeRemoved`, or at the first reconcile tick that observes the node absent from the draining set — not
  "when the node stops reporting DRAINING".**
- **Scope — which drains were frozen.** The "froze until the node halted" shape is the SELF-initiated drain, where
  the leader's FSM never saw a request: `QUORUM_LOSS` (`quorumLossChain`). A COMMANDED drain does not have it — both
  drain sinks route through `requestDrainThroughFsm` → `MembershipFsm.onDrainRequested` → `Departing`, whose
  `countsTowardEffective()` is false, so the target leaves `activeNodes()` at t0, before any DRAINING pong;
  `StaleEntryCleaner.cleanupStaleSliceEntries` then removes its slice entries at the end of each `reconcile()` and the
  next tick re-places from zero instances. For a commanded drain the pre-PR freeze was therefore bounded by ~2
  reconcile ticks, with the replacement coming from reconcile rather than from `NodeRemoved`. The deployment fixture
  keeps the drainee counted and so models the self-drain path; the commanded shape is now its own control
  [verified: `commandedDrain_uncountedDrainee_isReplacedByReconcileAndLeavesTheEvictionLoopNothingToDo` — with the
  drainee uncounted, one `reconcile()` issues removals for its slice entries AND the replacement LOAD, and the
  DRAINING report that follows issues nothing because the node no longer holds a slice]
  `[unverified: the `Departing`/uncounted transition itself is traced by reading `requestDrainThroughFsm` and
  `MembershipState`, not driven through a real FSM here]`.
- **Stuck evictions are retried without bound, and this PR makes that loop reachable rather than introducing it.**
  `deployReplacementForDrain` with no allocatable target re-schedules every 5 s; `checkReplacementAndUnload` with no
  ACTIVE replacement re-schedules every 3 s; neither has a bound, backoff, counter or metric, and the ACTIVE check
  does not treat a FAILED replacement as a reason to re-issue — `reconcileBlueprint` skips the blueprint while the
  node drains, so a replacement that lands FAILED is never retried. Since the loop always takes
  `slicesOnNode.getFirst()`, one stuck slice blocks every other slice on that node. Termination is the node leaving
  the draining set. `[design intent — unverified: no bound is added here; the FAILED-replacement case is filed
  separately]`
- Bounds, stated: the guard is in-memory and rebuilt empty on a new leader — correct, because the tick restarts from
  the draining set; a leader change mid-eviction re-issues nothing already ACTIVE (`tryAllocate` refuses a key already
  in `sliceStates`). The eviction does not reliably beat `DrainProcedure`: `maybeExit` halts the victim as soon as its
  in-flight tracker drains and the departure push settles — the grace deadline is only the backstop — so on an idle
  victim the loop's contribution is normally the first replacement LOAD, and a slice whose replacement is not ACTIVE
  before the halt is reallocated by `NodeRemoved` as before. `resumeDrainEvictions` now also fires for SELF when the
  local holder reports DRAINING, so a leader that self-drains on `QUORUM_LOSS` evicts its own slices; allocations
  cannot commit without quorum and the node self-fences, so today the cost is log lines during an outage — once
  #1089's commanded leader self-drain is DEPARTING/uncounted, `allocatableNodes()` excludes self and this becomes the
  desired graceful hand-off. `[unverified: worker drain path, by reading only — `ClusterSyncScheduler` sets the ping
  topology from core decisions, and workers pong `SpokesmanPingLoop`, which folds DRAINING into a count]`
