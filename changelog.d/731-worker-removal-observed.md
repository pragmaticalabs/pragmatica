### Fixed (2026-09-04 — #731: departed workers stayed in the cluster deployment FSM's allocation pool forever)

- **A dead worker's allocation-pool slot and KV footprint were never reclaimed — the symmetric gap
  left open by #728.** `MembershipDeltaProjector.processRemoved` emitted `NodeRemoved` only for
  members of the CORE `announced` baseline; a worker is deliberately kept out of that baseline (the
  #728 core-delta invariant), so its REMOVED edge returned early and
  `ClusterDeploymentState.handleNodeRemoval` was never reached for it. Of the state a dead worker
  left behind, only `workerNodes` (the in-memory allocation pool) and the worker's
  `ActivationDirectiveKey` had no code path to clear or re-place at all, regardless of how long the
  worker had been dead; its `SliceNodeKey`/`NodeArtifactKey`/`NodeRoutesKey` KV entries were
  reachable, if late, only through the pre-existing `StaleEntryCleaner` sweeps (see below).
  `processRemoved` now emits a new, symmetric `WorkerLeaveDecision` when
  `announcedWorkers.remove(node)` is true and the node is not core-announced; the cluster deployment
  FSM's new `WorkerLeaveReceived` arm runs the same `handleNodeRemoval(nodeId).onSuccess(_ ->
  reconcile())` the CORE arm already used, so a worker's clean-up and a core node's clean-up are the
  same code path. The CORE `NodeRemoved` arm is unchanged. `handleNodeRemoval` itself now also
  removes the departed node's `ActivationDirectiveKey` unconditionally — previously nothing ever
  issued `KVCommand.Remove` for it, so a dead worker's pool slot came back on the next leader
  activation (`restoreWorkerNode` re-adds any node whose surviving directive says WORKER, and
  `buildAllocationPool` copies `workerNodes` with no liveness intersection). A worker a new leader
  never locally observed joining — a fresh boot, or an asymmetric connection window — could still
  never be cleaned up even after that: `processRemoved`'s emission is gated on `everJoined`, upstream
  of the fix above, so `rebuildStateFromKVStore()` now runs a new sweep after restoring `workerNodes`
  from `ActivationDirectiveKey` entries, removing every restored worker absent from every
  non-dissolved community's `GovernorAnnouncementValue.members()` — the SWIM-derived roster each
  governor commits through consensus, the same record `WorkerRoutes` projects for `/api/workers` —
  through the same removal batch a live departure gets. An earlier cut of this sweep sourced
  liveness from `ctx.communityLiveness()` (pong-history-based) instead; on a cold leader with no pong
  history for anyone yet that source reads "not absent" for every restored worker, silently no-oping
  on exactly the scenario the sweep exists to catch, and in reverse could wrongly remove a live worker
  merely pong-silent past the absence window — SWIM membership has neither failure mode. If no
  community has announced yet when `rebuildStateFromKVStore()` runs (a genuinely cold leader), the
  sweep no-ops rather than guess; `Active.onEntry()`'s pre-existing 2s-deferred
  `deferredTopologyRecheck()` retries it once membership has had time to converge. The `SliceNodeKey`
  rows are a correction, not an addition: they were previously reachable, if late, through the
  pre-existing `StaleEntryCleaner` sweeps (which run against core-only `activeNodes()` and so treat
  any worker's rows as stale regardless of liveness — tracked separately as #850); `handleNodeRemoval`
  stripped the in-memory `sliceStates` entries and the `NodeArtifactKey` rows those sweeps read to
  find KV rows to remove, which made a `handleNodeRemoval`-cleaned worker's `SliceNodeKey` rows
  unreachable by every sweep. `handleNodeRemoval` now removes them from KV directly, in the same
  batch as `NodeArtifactKey`/`NodeRoutesKey`, mirroring the existing
  `cleanupAfterLifecycleDepartedAtomic` pattern.
  [verified: `ClusterDeploymentStateWorkerRemovalTest#workerLeave_departedWorker_clearsAllocationPoolAndKvFootprint`,
  `#workerLeave_outstandingBlueprintShortfall_reconcileRePlacesOntoRemainingPool`,
  `#workerRejoin_afterDeparture_reRegistersInAllocationPool`,
  `#leaderActivation_restoredWorkerAbsentFromCommunityMembership_isRemoved`,
  `#leaderActivation_restoredWorkerPresentInCommunityMembershipDespiteAbsentLiveness_isKept`,
  `#leaderActivation_coldLeaderNoAnnouncementYet_removesOnlyOnceMembershipConvergesAtDeferredRecheck`,
  `#coreNodeRemoved_membershipDecision_clearsSliceStateAndKvFootprint` (core-arm regression, confirmed
  unaffected); `MembershipDeltaProjectorTest$WorkerScoping#workerRemoval_emitsOnWorkerLeaveChannelOnly_coreArmUntouched`,
  `#workerRemoval_duplicateEdge_emitsLeaveOnce`,
  `#workerRejoinAfterRemoval_emitsAgain_becauseTheWorkerBaselineIsPruned` — driven in-process through
  the real projector/FSM harness (`FsmTestHarness`), not a live multi-node run; each test mutation-probed
  by reverting only its corresponding production hunk and confirming it fails]

- **This fix shares its liveness source with, but remains a distinct mechanism from, the
  operator-visible `/api/workers` roster.** `WorkerRoutes` (`GET /api/workers`) and the dead-worker
  sweep above now both project `GovernorAnnouncementValue.members()`, written by `GovernorAnnouncer`.
  `GovernorAnnouncer` updates its in-memory alive-member view on every SWIM membership edge, but the
  KV-committed announcement both mechanisms actually read is written immediately at governor election
  and, while the local node remains governor, only periodically thereafter
  (`GovernorAnnouncerRecord.tickReannounce`, default interval 30s) — so the roster is bounded by that
  reannounce interval, not edge-fresh, for both readers. A dead worker already ages out of
  `/api/workers` on its own within that bound; #731's sweep is what gives the CDM-internal allocation
  pool (`workerNodes`) and its KV footprint the same self-healing path, which they had none of before
  this change. No existing harness drives `GovernorAnnouncer`'s SWIM-reactive/periodic path end to end
  (`GovernorAnnouncerTest` covers only election-time writes), so the reannounce-interval bound is
  stated rather than pinned by a new test — exercising it is a separate concern from #731's scope.
  [mechanism: `GovernorAnnouncerRecord.onMembershipChange`/`tickReannounce`,
  `aether/node/src/main/java/org/pragmatica/aether/worker/governor/GovernorAnnouncer.java:126-244`]
