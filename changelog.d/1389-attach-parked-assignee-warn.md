### Fixed (2026-09-22 — #1389: a committed consumer assignee with no local slice was silent)

> **Evidence level for everything below: unit tests and source traces only.** No multi-node or forge run
> was made, so per the claim-discipline rule nothing here carries `[verified:]` — that tag is reserved
> for the live path. Test names are given so a reader can find the pin; they are pins, not verifications.

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
  [design intent — unverified; pinned by `StreamConsumerManagerTest$ParkedAssignment`, 8 enabled tests]
- **And counted**, which the ticket asked for and the first draft of this change did not do:
  `StreamConsumerManager.attachSkippedNoLocalSliceCount()`, surfaced as `attachSkippedNoLocalSliceCount`
  on `GET /api/v1/streams/declarative-consumers` alongside `cursorCommitFailureCount`, and documented in
  the management-API and CLI references. The unit is REPORTS, not partitions: one entry into the parked
  state naming four partitions adds one, and three reconcile passes over a persisting state add nothing —
  so a rise means the node went blind on a group, not that a tick noticed again. Node-wide, monotonic,
  reset only by a node restart. Both increment sites are separately pinned, because a counter is an
  instrument and an unpinned instrument fails silently later.
  [design intent — unverified; pinned by `reconcile_warnsOnce_whileTheParkedStatePersists`,
  `reconcile_warnsAndReportsTheParkedPartitions_…`, `attach_warns_…`, and
  `StreamRoutesDeclarativeConsumersTest`]
- **The report is keyed on BOTH halves — committed here AND still computed here — and the second half
  is what keeps it meaningful.** The committed record alone is also satisfied by a reassignment
  already decided: `publishAssignments` submits the leader's `Put` through consensus WITHOUT awaiting
  it, so the record outlives the decision by at least a round on every ordinary descale. Keying on the
  record alone would have fired the WARN on every descale in the cluster.
  [design intent — unverified; pinned by
  `reconcile_doesNotWarn_whenTheStaleRecordNamesThisNodeButThePassComputesItAway`]
- **Ticket item 2, stated: the fix shape is RE-ASSIGN, not refuse — and the re-assignment already
  existed.** `ConsumerAssignmentWriter.rewriteIfMoved` rewrites the record at `assignmentTerm + 1`
  whenever the computed assignee differs from the committed one, and `candidateNodes` restricts
  candidates to nodes where the deployment map reports the artifact ACTIVE, intersected with the live
  member view. No refuse/release path is added: when NO candidate exists the writer deliberately writes
  nothing, and that case is already the #535 `unassignedPartitions` ERROR, which removing the record
  would silence.
  [design intent — unverified; pinned by `reconcile_reassignsToARemainingCandidate_whenTheAssigneeLeavesActive`]
- **CORRECTION to an earlier draft of this note, which claimed more than the code does.** That draft
  said the assignment step "does not assign a partition to a node that holds no instance of the
  consuming slice", full stop. That is true of the deployment map's INTENDED contents, not of the
  computation: `candidateNodes` TRUSTS the map, so where the map is wrong the computation is wrong with
  it. And the map can be wrong — `NodeDeploymentState.handleReactivationFailure` and the quorum-loss
  `suspendSlice` both call `unregisterSliceFromInvocation` WITHOUT transitioning the deployment away
  from ACTIVE, unlike `handleUnloading` and `performDeactivation`, which commit UNLOADING /
  DEACTIVATING first. In that state this node stays the computed assignee, **no leader pass ever
  reassigns the partition**, and the WARN is the only observable. So the parked state is PERSISTENT, not
  merely a transient window — the two cases are now a matched pair of tests differing in exactly one
  input (whether placement transitions) and reaching opposite outcomes.
  [design intent — unverified; pinned by
  `reconcile_staysParkedAndNeverReassigns_whenTheBridgeGoesWithoutLeavingActive`]
  Whether those two paths should themselves transition the deployment is a separate defect, reported
  and not fixed here.
- The literal attach-time drop (`attachAdmitted` finding the bridge gone between the desired-set read
  and the attach) logs the same WARN, and counts, instead of forgetting the key silently. That one is
  unconditional: it is a within-pass race on a node this pass already computed as the assignee, so no
  in-flight reassignment can be confused with it.
  [design intent — unverified; pinned by `attach_warns_whenTheSliceVanishesBetweenTheDesiredSetAndTheAttach`]
- **Not fixed here, tripwired: the stall this ticket was filed from has a DIFFERENT producer, so
  #1389's own "Mechanism" paragraph is wrong about it.** A 5-minute window with no consumer anywhere
  cannot come from the attach drop, which self-repairs within a tick.
  `TopicSubscriptionKey(address, artifact, method)` carries no node, so every instance writes one shared
  KV entry and ONE instance's unload `Remove`s the record every OTHER instance's durable group is
  declared from; the group is un-declared cluster-wide, every node detaches on its next pass, and no
  diagnosis can report it because a dropped declaration has no status row.
  `tripwire_descaleOfAnotherInstance_undeclaresTheDurableGroupHere_untilTheSharedKeyIsNodeScoped` asserts
  that behaviour and goes red the moment the key becomes node-scoped or re-asserted, telling its reader
  to delete it and enable the `@Disabled` inverse beside it. Tracked by its own ticket.
  [design intent — unverified; induced at unit level only, not on a cluster]
