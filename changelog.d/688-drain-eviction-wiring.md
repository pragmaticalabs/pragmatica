### Fixed (2026-09-14 — #688: the leader's half of a node drain never ran; blueprints with an instance on a draining node froze until the node halted or the leader changed)
- **Finding 1, wired.** `ClusterDeploymentState.startDrainEviction` — deploy a replacement elsewhere, unload the
  original once it is ACTIVE — had one entry, the `MembershipDecision.NodeDraining` arm, and that decision has never
  been emitted in this repository's history (the `startDrainEviction` arm arrived with the aether-deployment import
  `7b79151ed`; the "no longer emitted — membership-v2 finale" note with `5e117fa51`, 2026-06-13; `git log -S` over
  `origin` and the 82 tags finds no producer — the archive tags are gone by ruling, so zero is expected). Meanwhile
  `reconcileBlueprint` SKIPS every blueprint with an instance on a draining node (`hasInstancesOnDrainingNodes`),
  deferring to that loop. So a node that reported DRAINING (`NodeReportedState.DRAINING` on its pong, the real
  signal, which already reached `MembershipFsm.onDrainAcknowledged` and the CDM's `drainingNodes()` supplier) froze
  its blueprints until `DrainProcedure` halted it and `NodeRemoved` reallocated.
  Now the same pong-fan report also drives `ClusterDeploymentManager.onNodeDraining` (new receiver, dispatched as
  `ClusterDeploymentEvents.NodeDrainingReported`), wired in `AetherNode.drainReportListener`
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/AetherNodeDrainReportListenerTest.java`;
  `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/cluster/fsm/ClusterDeploymentStateDrainEvictionTest.java`
  — a DRAINING report for a node holding an ACTIVE slice issues exactly one replacement LOAD on another node; the
  legacy decision arm is kept and pinned as the fixture control]. The `NodeDraining` arm is kept (it routes to the
  same guarded entry), not deleted: it is the harness path three test suites drive.
- **Finding 2, a periodic trigger.** `resumeDrainEvictions` ran only on leader activation; it now runs on every
  reconcile tick too, restarting the loop for any node the draining set names that has no loop running. A per-node
  guard (`drainEvictionsInProgress`, in-memory, cleared on `completeDrain`, `NodeRemoved`, and when the node stops
  reporting DRAINING) makes both entries idempotent per drain episode — the pong repeats every ping interval and the
  tick every reconcile interval, and neither may double-issue a replacement
  [verified: same class — `reconcile_withADrainingNodeHoldingASlice_issuesTheReplacementLoad` (red at the base: the
  blueprint was skipped and nothing else ran), `drainingReport_startsTheEvictionOnce_andRepeatsDoNotDoubleIssue`,
  `drainWithdrawn_thenReported_again_startsAFreshEviction`. Removing the tick call reddens the first and third;
  removing the guard reddens the second; dropping the CDM call from the listener reddens the node seam test].
- Bounds, stated: the guard is a judgment and is rebuilt empty on a new leader — correct, because the tick restarts
  from the draining set; a leader change mid-eviction re-issues nothing already ACTIVE (`tryAllocate` refuses a key
  already in `sliceStates`). The eviction still races `DrainProcedure`'s grace deadline: a slice whose replacement
  is not ACTIVE before the victim halts is reallocated by `NodeRemoved` as before. `[unverified: no multi-node drain
  run; all pins drive the real `Active` state over an in-process store]`
