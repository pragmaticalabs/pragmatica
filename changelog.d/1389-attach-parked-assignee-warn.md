### Fixed (2026-09-22 — #1389: a committed consumer assignee with no local slice was silent)
- **A node the committed assignment record names, whose invocation handler has no bridge for the
  slice, consumed nothing and said nothing.** `StreamConsumerManager.desiredFor` returned an empty
  desired set without a word, and `Diagnosis.log` could even emit the routine "consuming partitions
  … forwarded to the owner" INFO for a node that consumes nothing. The only observable was growing
  lag, and no log line an operator could search for.
- The state is now a FAULT: `Diagnosis` carries `parkedPartitions`, the `diagnostic` field of
  `statuses()` / `topicGroupStatuses()` names group, stream, partitions, this node and the missing
  slice, and the transition is logged at WARN before the forwarding INFO can claim otherwise.
  Transition-logged like every other diagnosis — the field stays set while the state persists; the
  line fires once per change.
  [verified: `StreamConsumerManagerTest$ParkedAssignment`, 7 enabled tests, 1 disabled inverse]
- **And counted**, which the ticket asked for: `StreamConsumerManager.attachSkippedNoLocalSliceCount()`,
  surfaced as `attachSkippedNoLocalSliceCount` on `GET /api/v1/streams/declarative-consumers`
  alongside `cursorCommitFailureCount`, and documented in the management-API and CLI references. The
  unit is REPORTS, not partitions: one entry into the parked state naming four partitions adds one,
  and three reconcile passes over a persisting state add nothing — so a rise in it means the node
  went blind on a group, not that a tick noticed again. Node-wide, monotonic, reset only by a node
  restart.
  [verified: `reconcile_warnsOnce_whileTheParkedStatePersists`,
  `reconcile_warnsAndReportsTheParkedPartitions_whenCommittedHereButTheSliceIsNotLoadedHere`]
- **The report is keyed on BOTH halves — committed here AND still computed here — and the second half
  is what keeps it meaningful.** The committed record alone is also satisfied by a reassignment
  already decided: `publishAssignments` submits the leader's `Put` through consensus WITHOUT awaiting
  it, so the record outlives the decision by at least a round on every ordinary descale. Keying on the
  record alone would have fired the WARN on every descale in the cluster. What the intersection keeps
  is the state nothing repairs — the deployment map still reports the slice ACTIVE here, so
  `candidateNodes` keeps computing this node, while no bridge is loaded.
  [verified: `reconcile_doesNotWarn_whenTheStaleRecordNamesThisNodeButThePassComputesItAway`]
- The literal attach-time drop (`attachAdmitted` finding the bridge gone between the desired-set read
  and the attach) logs the same WARN, and counts, instead of forgetting the key silently. That one is
  unconditional: it is a within-pass race on a node this pass already computed as the assignee, so no
  in-flight reassignment can be confused with it.
  [verified: `attach_warns_whenTheSliceVanishesBetweenTheDesiredSetAndTheAttach`]
- **Ticket item 2, stated: the assignment protocol RE-ASSIGNS, it does not refuse — and it already
  did.** `candidateNodes` restricts candidates to nodes where the deployment map reports the artifact
  ACTIVE, intersected with the live member view, so the computation never picks a node that holds no
  instance; `ConsumerAssignmentWriter.rewriteIfMoved` rewrites the record at `assignmentTerm + 1`
  whenever the computed assignee differs from the committed one. No refuse/release path is added: when
  NO candidate exists the writer deliberately writes nothing, and that case is already the #535
  `unassignedPartitions` ERROR, which removing the record would silence.
  [verified: `reconcile_reassignsToARemainingCandidate_whenTheAssigneeLeavesActive`]
- **The parked state is NOT merely transient, and the earlier draft of this note said it was.** The
  repair above depends on the deployment leaving ACTIVE. `NodeDeploymentState.handleUnloading` and
  `performDeactivation` commit UNLOADING / DEACTIVATING before unregistering the bridge, so those
  paths do self-repair. `handleReactivationFailure` and the quorum-loss `suspendSlice` path both
  unregister the slice from invocation WITHOUT transitioning the deployment away from ACTIVE — in that
  state the leader keeps computing this node forever and the WARN is the only observable. Operator
  recovery is named on the management-API page: redeploy or unload the slice here.
  [unverified: the two ACTIVE-without-bridge paths are read from `NodeDeploymentState`, not reproduced
  at runtime]
- **Not fixed here, tripwired: the stall this ticket was filed from has a DIFFERENT producer.** A
  5-minute window with no consumer anywhere cannot come from the attach drop, which self-repairs
  within a tick. `TopicSubscriptionKey(address, artifact, method)` carries no node, so every instance
  writes one shared KV entry and ONE instance's unload `Remove`s the record every OTHER instance's
  durable group is declared from; the group is un-declared cluster-wide, every node detaches on its
  next pass, and no diagnosis can report it because a dropped declaration has no status row.
  `tripwire_descaleOfAnotherInstance_undeclaresTheDurableGroupHere_untilItem3IsFixed` asserts that
  behaviour and goes red the moment the key becomes node-scoped or re-asserted.
  [unverified: pinned at unit level only; not reproduced at runtime]
