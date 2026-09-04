### Fixed (2026-09-04 — #731: departed workers stayed in the cluster deployment FSM's allocation pool forever)

- **A dead worker's allocation-pool slot and KV footprint were never reclaimed — the symmetric gap
  left open by #728.** `MembershipDeltaProjector.processRemoved` emitted `NodeRemoved` only for
  members of the CORE `announced` baseline; a worker is deliberately kept out of that baseline (the
  #728 core-delta invariant), so its REMOVED edge returned early and
  `ClusterDeploymentState.handleNodeRemoval` was never reached for it. `workerNodes`, the worker's
  `SliceNodeKey`/`NodeArtifactKey`/`NodeRoutesKey`/`ActivationDirectiveKey` KV entries, and any
  blueprint shortfall its departure created had no code path to clear or re-place them, regardless of
  how long the worker had been dead. `processRemoved` now emits a new, symmetric
  `WorkerLeaveDecision` when `announcedWorkers.remove(node)` is true and the node is not
  core-announced; the cluster deployment FSM's new `WorkerLeaveReceived` arm runs the same
  `handleNodeRemoval(nodeId).onSuccess(_ -> reconcile())` the CORE arm already used, so a worker's
  clean-up and a core node's clean-up are the same code path. The CORE `NodeRemoved` arm is
  unchanged. `handleNodeRemoval` itself now also removes the departed node's `ActivationDirectiveKey`
  unconditionally — previously nothing ever issued `KVCommand.Remove` for it, so a dead worker's pool
  slot came back on the next leader activation (`restoreWorkerNode` re-adds any node whose surviving
  directive says WORKER, and `buildAllocationPool` copies `workerNodes` with no liveness
  intersection). A worker a new leader never locally observed joining — a fresh boot, or an
  asymmetric connection window — could still never be cleaned up even after that: `processRemoved`'s
  emission is gated on `everJoined`, upstream of the fix above, so `rebuildStateFromKVStore()` now
  runs a new sweep after restoring `workerNodes` from `ActivationDirectiveKey` entries, removing every
  restored worker the leader's `CommunityLivenessView` currently reports absent through the same
  removal batch a live departure gets. The `SliceNodeKey` rows are a correction, not an addition: they
  were previously reachable, if late, through the pre-existing `StaleEntryCleaner` sweeps (which run
  against core-only `activeNodes()` and so treat any worker's rows as stale regardless of liveness —
  tracked separately as #850); `handleNodeRemoval` stripped the in-memory `sliceStates` entries and
  the `NodeArtifactKey` rows those sweeps read to find KV rows to remove, which made a
  `handleNodeRemoval`-cleaned worker's `SliceNodeKey` rows unreachable by every sweep.
  `handleNodeRemoval` now removes them from KV directly, in the same batch as
  `NodeArtifactKey`/`NodeRoutesKey`, mirroring the existing `cleanupAfterLifecycleDepartedAtomic`
  pattern.
  [verified: `ClusterDeploymentStateWorkerRemovalTest#workerLeave_departedWorker_clearsAllocationPoolAndKvFootprint`,
  `#workerLeave_outstandingBlueprintShortfall_reconcileRePlacesOntoRemainingPool`,
  `#workerRejoin_afterDeparture_reRegistersInAllocationPool`,
  `#leaderActivation_restoredWorkerObservedAbsentFromLiveness_isRemoved`,
  `#coreNodeRemoved_membershipDecision_clearsSliceStateAndKvFootprint` (core-arm regression, confirmed
  unaffected); `MembershipDeltaProjectorTest$WorkerScoping#workerRemoval_emitsOnWorkerLeaveChannelOnly_coreArmUntouched`,
  `#workerRemoval_duplicateEdge_emitsLeaveOnce`,
  `#workerRejoinAfterRemoval_emitsAgain_becauseTheWorkerBaselineIsPruned` — driven in-process through
  the real projector/FSM harness (`FsmTestHarness`), not a live multi-node run; each test mutation-probed
  by reverting only its corresponding production hunk and confirming it fails]

- **This fix does not touch the operator-visible `/api/workers` roster — a separate mechanism that was
  not actually stuck forever.** `WorkerRoutes` (`GET /api/workers`) projects
  `GovernorAnnouncementValue.members()`, written by `GovernorAnnouncer`, an independent component that
  already refreshes on every SWIM membership edge and, while the local node remains governor,
  periodically rewrites the announcement from live membership
  (`GovernorAnnouncerRecord.tickReannounce`, default interval 30s). A dead worker already ages out of
  that roster on its own, bounded by the reannounce interval; #731's fix is scoped to the CDM-internal
  allocation pool (`workerNodes`) and its KV footprint, which had no self-healing path at all before
  this change. No existing harness drives `GovernorAnnouncer`'s SWIM-reactive/periodic path end to end
  (`GovernorAnnouncerTest` covers only election-time writes), so this boundary is stated rather than
  pinned by a new test — exercising it is a separate concern from #731's scope.
  [mechanism: `GovernorAnnouncerRecord.onMembershipChange`/`tickReannounce`,
  `aether/node/src/main/java/org/pragmatica/aether/worker/governor/GovernorAnnouncer.java:126-244`]
