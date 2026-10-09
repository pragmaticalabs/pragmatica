### Fixed (2026-10-09 — #2062: every retirement reap is a confirmed, bounded, operator-visible reap)
- **Retiring a bootstrap node no longer leaks its instance.** `terminateRetired` and the drain-grace backstop reaped through
  `terminateNode(NodeId)`, which refuses when the node has no capacity reservation (a bootstrap node has one only after a listing observed
  it) and then logged and dropped: scale-down, departure and drain-then-retire left the VM running and billing. They now go through the confirmed
  reap `ClusterTopologyManager.reapRetired(node, source, seenBefore)` introduced for replacements (#1543): list (which commits the missing
  reservation), terminate, re-list, and confirm.
- **Retried and bounded, then an operator event.** A reap that fails or cannot be confirmed is retried 12 times, one sixth of a provisioning window
  apart; when the bound is spent, `instance-termination-unconfirmed` names the node and the last cause (its recovery is
  `instance-termination-confirmed`). The refused-reap chain (a voter that never becomes retirable) ends in the same event instead of a log line.
- **What "gone" means.** A listing that fails is never "gone". An empty listing is gone only for an instance the provider listed before, or after a
  terminate it accepted; a STOPPED machine is not gone (it still bills): it is terminated. The reap is idempotent per node (NodeRemoved and the
  drain-grace backstop both reap), and a provider whose listing lags its delete is confirmed by the retry.
- **EXTERNAL nodes make no provider call on any path.** A node whose reservation carries no provider binding (an operator started it) is confirmed by
  leaving the membership; its capacity is returned and terminating it through the lifecycle is a no-op.
- [verified: real `SourceComputeRegistry` + real `CapacityControlledLifecycle` + real topology manager, `NodeReplacementRealRegistryReapTest` (19
  tests): scale-down of an unlisted bootstrap node, listing error retried, event and its recovery only on a real confirmation, stopped instance
  terminated, lagging listing, second reap of a confirmed and of an external node, refused chain ends in the event; `ClusterTopologyManagerReapRetiredTest`;
  the operator-warning sink wiring is pinned by a boot test.] [unverified: a real cloud run; Ember acceptance (0 refusals per class run) is pending.]
