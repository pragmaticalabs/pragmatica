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
  `deferredTopologyRecheck()` retries it, and — round 3 below — every committed reannouncement
  retries it again immediately, bounding eventual removal by SWIM detection time plus at most one
  reannounce interval (frozen, not resolved, under a partition — the safe direction). The `SliceNodeKey`
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
  `#leaderActivation_restoredWorkerAbsentFromAnnouncementButPresentInLocalSwimView_isKept` (round 3),
  `#coreNodeRemoved_membershipDecision_clearsSliceStateAndKvFootprint` (core-arm regression, confirmed
  unaffected); `MembershipDeltaProjectorTest$WorkerScoping#workerRemoval_emitsOnWorkerLeaveChannelOnly_coreArmUntouched`,
  `#workerRemoval_duplicateEdge_emitsLeaveOnce`,
  `#workerRejoinAfterRemoval_emitsAgain_becauseTheWorkerBaselineIsPruned` — driven in-process through
  the real projector/FSM harness (`FsmTestHarness`), not a live multi-node run; each test mutation-probed
  by reverting only its corresponding production hunk and confirming it fails]

- **Round 3 (2026-09-04 review re-check): the committed announcement alone still had a lag a fresh
  worker could fall into.** A worker enters `workerNodes` the instant its `ActivationDirectiveKey`
  commits, but only enters a `GovernorAnnouncementValue.members()` roster at the governor's next
  `tickReannounce` — `GovernorAnnouncerRecord.onSelfElected` returns early (no fresh write) when the
  node is already governor, and a SWIM edge in between only updates the governor's in-memory
  `lastAliveMembers`, never the committed value — so for up to one reannounce interval (default 30s)
  a live, freshly-committed worker was in no announcement at all, and either the immediate sweep or
  the 2s deferred recheck landing inside that window would have swept it as dead. `GovernorAnnouncer`
  instances never run on a CORE leader's own process (mutually exclusive roles — an announcer exists
  only on a node that itself received a WORKER directive), so `sweepDeadRestoredWorkers` cannot read
  `lastAliveMembers` directly; it now takes a second, in-process-local signal instead:
  `ctx.localAliveMembersSupplier()`, wired in `AetherNode` to `MembershipFsm.dhtRoutableMembers()`
  (OBSERVED+MEMBER+SUSPECT — the same authoritative, SWIM-fed, role-blind membership view already
  used elsewhere in this FSM, just not previously threaded into this sweep). A worker is removed only
  when it is absent from BOTH the committed announcement AND this local view; a fresh live worker is
  present locally within SWIM detection time even before its first reannouncement, and a genuinely
  dead worker is absent from both within that same detection time.
  [mechanism: `ClusterDeploymentState.sweepDeadRestoredWorkers` (dual-signal filter),
  `MembershipFsm.dhtRoutableMembers`, `GovernorAnnouncerRecord.onSelfElected`/`tickReannounce`
  (`aether/node/src/main/java/org/pragmatica/aether/worker/governor/GovernorAnnouncer.java:138-244`)]
  [verified: `ClusterDeploymentStateWorkerRemovalTest#leaderActivation_restoredWorkerAbsentFromAnnouncementButPresentInLocalSwimView_isKept`]

- **Round 3: the sweep also now re-runs on every committed governor reannouncement, not only from the
  pre-existing one-shot 2s deferred recheck.** New `ClusterDeploymentManager.onGovernorAnnouncementPut`
  handles `GovernorAnnouncementPutReceived` and re-invokes the sweep on every committed
  `GovernorAnnouncementKey` Put, dispatched through `KVNotificationRouter`'s existing per-key listener
  list — appended, not displacing the pre-existing `ownershipEpochHighWater` listener on the same key
  — rather than only from the pre-existing one-shot 2s `deferredTopologyRecheck` timer, which stays in
  place unchanged for its other unconditional cleanup and `reconcile()` calls. This shortens the
  worst-case removal delay to the dual-signal detection time above on every reannounce, not only once
  at Active entry. No test exercises this dispatch directly; its failure mode is silent non-firing,
  which only widens the window back to the pre-existing 2s deferred-recheck bound — the safe direction.
  [mechanism: `ClusterDeploymentManager.onGovernorAnnouncementPut`, `KVNotificationRouter` dispatch]

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
