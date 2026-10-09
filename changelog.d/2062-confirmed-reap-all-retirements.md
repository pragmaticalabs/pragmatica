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
  terminate it accepted; a STOPPED machine is not gone (it still bills): it is terminated. The reap is idempotent per node incarnation (NodeRemoved and the
  drain-grace backstop both reap the same one; a node that joins again is a new incarnation, and what was remembered of the previous one is dropped), and a provider whose listing lags its delete is confirmed by the retry.
- **EXTERNAL nodes make no provider call on any path.** A node whose reservation carries no provider binding (an operator started it) is confirmed by
  leaving the membership; its capacity is returned and terminating it through the lifecycle is a no-op.
- [verified: real `SourceComputeRegistry` + real `CapacityControlledLifecycle` + real topology manager, `NodeReplacementRealRegistryReapTest` (42
  tests): scale-down of an unlisted bootstrap node, listing error retried, event and its recovery only on a real confirmation, stopped instance
  terminated, lagging listing, second reap of a confirmed and of an external node, refused chain ends in the event; `ClusterTopologyManagerReapRetiredTest`;
  the operator-warning sink wiring is pinned by a boot test.] [verified: Ember class x2, 0 refusals per run.] [unverified: a real cloud run.]
- **The manager owns the unconfirmed-termination lifecycle.** `ClusterTopologyManager.markUnconfirmed(node, cause)` marks the node and raises
  `instance-termination-unconfirmed` (once); every raiser goes through it, and a fresh orphan found by the activation replay is marked only after its
  terminate attempts have failed. While it is the active leader the manager re-checks each marked node at a low bounded rate (one confirmed-reap attempt per
  node per five provisioning windows) and raises `instance-termination-confirmed` on a real confirmation. A node that shows life is never terminated or
  confirmed gone by the re-check, by a retry of the confirmed reap, or by the memory of an earlier reap. The marks live in the replicated store
  (`UnconfirmedTerminationKey`/`Value`, wire tags 2132/2133, runtime state): written by `markUnconfirmed`, removed on the recovery. A manager that has just
  become leader inherits every persisted mark without announcing it again, re-checks it at once, and closes it when the instance is gone; the recovery
  is published by whichever node confirms it, so it closes the warning the previous leader raised (`OperatorWarningCode#closesAcrossNodes`).
- **A mark whose instance was never listed is an operator warning, not a pending check.** The provider has never listed the instance (an unlabelled VM, or
  a provider that only ever failed), so no listing can confirm it: it is not re-checked, and its event says the cluster cannot confirm it and the operator
  must verify at the provider and terminate it by hand. An operator clear path is a follow-up. A mark whose instance WAS listed keeps being re-checked.
- **A node that joins again is a new incarnation.** What the manager remembered of the previous one (its reap was confirmed, its instance was seen, its
  termination was unconfirmed) is dropped on its join, so a reused id is reaped, not "confirmed gone" while it is a member. The release of a counted EXTERNAL
  reservation against an empty ledger is refused as inconsistent, as the release of a provider reservation is, instead of clamping at zero.
