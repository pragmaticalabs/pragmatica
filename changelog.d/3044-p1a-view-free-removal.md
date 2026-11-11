### Added (2026-11-11 — #3044 P1a: a view-free removal for the leader FSM's stored view)
- `LeaderElectionContext.removeFromView(NodeId)` removes exactly one node from the stored view, atomically with every
  other view write (one pure `advance` over a candidate that is either a wholesale replace or a removal). It never adds
  a node and never moves the sequence, so only a newer transport view undoes it; it keeps the view when the node is
  absent, is this node, or is the last node left. `LeaderElectionEvents.PeerRemovedFromView(NodeId)` is handled in the
  seven states that handle `NodeGone`; in `Led`, removing the leader loses the leader whatever the storage kept, and in
  `Electing`/`ReElecting` an applied removal reschedules the tick. Additive: nothing in production dispatches the
  event yet (P1b wires the SWIM, eligibility and local-mode writers to it and drops unsequenced wholesale writes).
