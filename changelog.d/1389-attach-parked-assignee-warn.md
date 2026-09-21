### Fixed (2026-09-22 — #1389: a committed consumer assignee with no local slice was silent)
- **A node the committed assignment record names, whose invocation handler has no bridge for the
  slice, consumed nothing and said nothing.** `StreamConsumerManager.desiredFor` returned an empty
  desired set without a word, and `Diagnosis.log` could even emit the routine "consuming partitions
  … forwarded to the owner" INFO for a node that consumes nothing. The leader keeps naming that node
  for as long as the deployment map still says ACTIVE there, so the only observable was growing lag.
- The parked state is now a FAULT: `Diagnosis` carries `parkedPartitions` (committed here, slice not
  loaded here), the `diagnostic` field of `statuses()` / `topicGroupStatuses()` names group, stream,
  partitions, this node and the missing slice, and the transition is logged at WARN before the
  forwarding INFO can claim otherwise. Transition-logged like every other diagnosis — the field stays
  set while the state persists; the log line fires once per change.
  [verified: `StreamConsumerManagerTest$ParkedAssignment`]
- The literal attach-time drop (`attachAdmitted` finding the bridge gone between the desired-set read
  and the attach) logs the same WARN instead of forgetting the key silently.
  [verified: `attach_warns_whenTheSliceVanishesBetweenTheDesiredSetAndTheAttach`]
- The diagnosis is now recorded AFTER the pass's assignment publish, so a leader that just moved a
  partition away from itself does not report itself parked on it for one pass.
- **Ticket item 2, stated:** the assignment protocol RE-ASSIGNS, it does not refuse. Every node-side
  unload path (`handleUnloading`, `performDeactivation`) commits UNLOADING / DEACTIVATING before it
  unregisters the bridge, the leader's candidate set is ACTIVE-only, and the writer rewrites on the
  next pass — pinned by `reconcile_reassignsToARemainingCandidate_whenTheAssigneeLeavesActive`. No
  refuse/release path is added: the parked state is transient unless the deployment map is stale,
  and then the WARN is the operator's signal.
- **Not fixed here, tripwired:** the M1 stall the ticket was filed from has a different producer.
  `TopicSubscriptionKey(address, artifact, method)` carries no node, so ONE instance's unload
  `Remove`s the record every other instance's durable group is declared from; the group is
  un-declared cluster-wide, the consumer detaches on the next pass, and no diagnosis exists to report
  it. `tripwire_descaleOfAnotherInstance_undeclaresTheDurableGroupHere_untilItem3IsFixed` asserts
  that behaviour and goes red the moment the key becomes node-scoped or re-asserted.
  [unverified: not reproduced at runtime; pinned at unit level only]
