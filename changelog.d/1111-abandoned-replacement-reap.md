### Fixed (2026-10-09 — #1111: a replacement the leader gives up on was dropped but never terminated)
- **A billed orphan until the next leadership change.** When the `LeaderReconciler` dropped an in-flight auto-heal replacement (its
  ceiling passed, the provider stopped listing it after twelve absent listings or reported it failed, or its provision call failed,
  which includes a readiness timeout) it terminated nothing; only an activation replay reaped the instance, and a stable leader never
  replays. It now reaps the instance through the confirmed `ClusterTopologyManager.reapRetired`, which terminates it and re-lists the
  provider to confirm it is gone. An instance the provider never listed (twelve absent listings, a ceiling on an unconfirmed entry, a failed
  provision call) is reaped as unseen: an empty listing does not confirm it gone, so it stays unconfirmed (retried, then announced) unless a
  listing shows it and a terminate is accepted. A readiness timeout is treated the same way, because an empty listing after it may be a
  lagging one. The entry leaves the in-flight map before the reap is requested, for every reason.
  [verified: aether/aether-deployment LeaderReconcilerTest$AbandonedReplacementReap, one test per drop reason; in-JVM against a fake topology manager, no live cloud]
- **The record names the instance.** The WARN `abandoned in-flight replacement` carries the source, the node id and the provider instance id.
  [verified: LeaderReconcilerTest$AbandonedReplacementReap.abandonment_warnsWithSourceNodeIdAndInstanceId]
- **A reap that cannot be confirmed is retried, then announced, then left.** Five attempts at the poll interval; the fifth failure hands the
  orphan to the topology manager (`markUnconfirmed`), the single raiser, which raises the operator event
  `instance-termination-unconfirmed` once (subject: the replacement's node id; the message names the instance and the cause). The active
  retries then STOP: no provider call is made for that replacement afterwards. Because the manager remembers the node, a later confirmed
  reap of it (the grace backstop, the activation replay, or the manager's own periodic re-check, #2062) raises `instance-termination-confirmed` for the same subject.
  Operator recovery: terminate the named instance at the provider. Retries also stop when the replacement joins membership or leadership
  changes.
  [verified: LeaderReconcilerTest$AbandonedReplacementReap.unconfirmedReap_isRetriedWithinABound_thenAnnouncedNamingTheInstance, afterTheBound_theEventFiresOnce_andNoProviderCallIsMade; ClusterTopologyManagerReapRetiredTest.announcedUnconfirmedTermination_firesOnce_andIsClearedByALaterConfirmedReap]
- **An explicit create refusal is not an orphan.** A typed refusal (`ProvisionFailed`, `CapacityUnavailable`, `CredentialsMissing`,
  `NodeCapExceeded`, `OperationNotSupported`; an all-zones-full rotation now returns `CapacityUnavailable`) means nothing was created: it is
  neither reaped nor announced, and stays a provisioning failure (the manager's breaker and log). A readiness timeout, which carries an instance
  id, is reaped.
  [verified: LeaderReconcilerTest$AbandonedReplacementReap.capacityRefusal_isNeitherReapedNorAnnounced, provisionFailedRefusal_..., unsupportedOperationRefusal_..., aReadinessTimeout_isNotARefusal_itIsReaped; ClusterTopologyManagerZoneRotationTest$Exhaustion]
- **A replacement that joined is never reaped**, including one whose ceiling evicts it before the reconcile pass that would clear it.
  [verified: LeaderReconcilerTest$AbandonedReplacementReap.joinedReplacement_isNeverReaped_whenItsCeilingPasses, retriedReap_stopsWhenTheReplacementJoins]
- [design intent — unverified: no run against a live provider; a leader that loses leadership mid-retry leaves the instance to the next activation replay]
- [unverified: the reconciler tests use a fake topology manager that restates the empty-listing rule (the rule itself is pinned against the real manager in ClusterTopologyManagerReapRetiredTest); the manager's periodic re-check, KV persistence and successor inheritance of the mark are #2062's and are not exercised by this change's tests]
