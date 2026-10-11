### Fixed (2026-10-11 — #1111: a replacement the leader gives up on is terminated, not orphaned)
- **Every drop of an in-flight auto-heal replacement now reaps its instance.** `LeaderReconciler` dropped an entry at the per-source replacement
  ceiling, after twelve absent listings, on a FAILED provider report, and when the create failed (a readiness timeout), and in each case only logged
  at INFO: the paid instance stayed running on a stable leader until the next leadership change. Each drop now calls
  `ClusterTopologyManager.reapDroppedReplacement(node, reason, seenBefore)`, which goes through the confirmed reap built for retirements (#2062):
  list, terminate, re-list, retried 12 times and bounded.
- **Operator-visible.** The manager WARNs with the source, the node id and the instance id(s) the provider lists for it, then terminates. A termination
  that cannot be confirmed ends in the same `instance-termination-unconfirmed` mark and event as a retirement (recovery:
  `instance-termination-confirmed`).
- **A joined replacement is never terminated.** Membership clears the entry of a replacement that joined, so it is never dropped; and a dropped one that
  shows life when the reap runs is deferred and never terminated, exactly as a retirement is.
- **What "confirmed" means here.** An empty listing is gone only for an instance the provider listed before (a PRESENT, CONFIRMED or FAILED report);
  twelve empty listings of an instance never listed do not confirm, because a listing lags creation and omits what it cannot attribute: that drop ends
  in `instance-termination-unconfirmed` with the "never listed" text, asking the operator to verify at the provider. A create that was deferred (circuit open, no
  healthy peers) made no instance and reaps nothing; neither does a create the provider rejected (quota, capacity, an API error): it is WARNed with
  its cause, since a reap of a VM that never existed would raise a false unconfirmed event. Only a readiness timeout (the instance was made) is reaped.
  A replacement alive in SWIM but not yet joined when its ceiling or readiness bound expires is given up on by design and can be terminated.
- [verified: `ClusterTopologyManagerDroppedReplacementTest` (real lifecycle, fake provider: terminate with the instance id in the WARN, unconfirmed
  event, confirmed-without-event, never-listed, no-source, live node never terminated) and `LeaderReconcilerTest$InFlightInstanceState` (one test per drop
  reason, deferral and joined control, once-only).] [unverified: a real cloud run; the Ember replacement test exercises the path without a billed
  provider.]
