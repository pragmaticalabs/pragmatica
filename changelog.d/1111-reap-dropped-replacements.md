### Fixed (2026-10-11 — #1111: a replacement the leader gives up on is terminated, not orphaned)
- **Every drop of an in-flight auto-heal replacement now reaps its instance.** `LeaderReconciler` dropped an entry at the per-source replacement
  ceiling, after twelve absent listings, on a FAILED provider report, and when the create failed, and in each case only logged at INFO: the paid
  instance stayed running on a stable leader until the next leadership change. Each drop now calls
  `ClusterTopologyManager.reapDroppedReplacement(node, reason, knowledge)`, which looks the instance up by its node-id label at the source the create
  went to, WARNs with the source, the node id and the instance id(s), and terminates it through the confirmed reap built for retirements (#2062):
  list, terminate, re-list, retried 12 times and bounded.
- **Operator-visible.** A termination that cannot be confirmed ends in the same `instance-termination-unconfirmed` mark and event as a retirement
  (recovery: `instance-termination-confirmed`). A lookup that cannot be answered is never read as "absent": it is retried and ends in that event.
- **A failed create is reaped unless the cause PROVES no instance was made.** The providers report a readiness timeout, a 5xx after the provider
  accepted, a lost response and a failed tag step as ordinary provision failures while the instance exists and bills, so only a capacity refusal
  (`CapacityUnavailable`: Hetzner `resource_unavailable`, AWS `InsufficientInstanceCapacity`, and every configured zone refusing), the fleet cap
  (`NodeCapExceeded`) and a refused fleet reservation (`CapacityControlledLifecycle.AdmissionFailure`) skip the lookup. Every other failure is looked
  up; if `12` consecutive successful listings show no instance (the absent-listing count of #1049), the drop is confirmed absent: an INFO line, no mark and no event.
- **The readiness timeout keeps its type.** `createFrom` of Hetzner, AWS, GCP, Azure and Docker flattened `ProvisionReadinessTimeout` into
  `ProvisionFailed`; each now passes it through (the instance exists).
- **A joined replacement is never terminated.** Membership clears the entry of a replacement that joined, so it is never dropped; and a dropped node
  that shows life when the reap runs is deferred, as a retirement is. A replacement alive in SWIM but not yet joined when its ceiling or readiness bound
  expires is given up on by design.
- **What "confirmed" means.** An empty listing is gone only for an instance the provider listed before; twelve empty listings of an instance a create
  returned but no listing ever showed do not confirm (a listing lags creation and omits what it cannot attribute): that drop ends in
  `instance-termination-unconfirmed` with the "never listed" text, asking the operator to verify at the provider. A create that was deferred (circuit
  open, no healthy peers) made no instance and reaps nothing.
- [verified: `LeaderReconcilerTest$RealCreatePath` (the reconciler on the real topology manager and lifecycle over a fake provider whose create makes an
  instance and then fails: readiness timeout, 5xx after accept, tag failure are terminated; capacity refusal is never looked up; a create that made
  nothing is confirmed absent with no event), `ClusterTopologyManagerDroppedReplacementTest`, `LeaderReconcilerTest$InFlightInstanceState`, and one
  `createFrom` readiness test per provider asserting the typed cause.] [unverified: a real cloud run; a provider's transport timeout shapes were not
  enumerated, they are treated as ambiguous and looked up.]
