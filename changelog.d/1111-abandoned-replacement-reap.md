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
- **A reap that cannot be confirmed is retried, then announced.** Five attempts at the poll interval; the fifth failure raises the operator
  event `instance-termination-unconfirmed` (subject: the replacement's node id; the message names the instance and the cause), after which the leader
  keeps retrying every four intervals and raises `instance-termination-confirmed` when the provider's listing shows the instance gone.
  Operator recovery: terminate the named instance at the provider. Retries stop when the replacement joins membership or leadership changes
  (the next leader's activation replay reaps what is still labelled and unowned).
  [verified: LeaderReconcilerTest$AbandonedReplacementReap.unconfirmedReap_isRetriedWithinABound_thenAnnouncedNamingTheInstance_andClosedOnSuccess]
- **A replacement that joined is never reaped**, including one whose ceiling evicts it before the reconcile pass that would clear it.
  [verified: LeaderReconcilerTest$AbandonedReplacementReap.joinedReplacement_isNeverReaped_whenItsCeilingPasses, retriedReap_stopsWhenTheReplacementJoins]
- [design intent — unverified: no run against a live provider; a leader that loses leadership mid-retry leaves the instance to the next activation replay]
- [unverified: the reconciler tests use a fake topology manager that restates the empty-listing rule (the rule itself is pinned against the real manager in ClusterTopologyManagerReapRetiredTest); after a bound-exhausted event the slow retries never stop for an instance that was never created, until the node joins or leadership changes]
