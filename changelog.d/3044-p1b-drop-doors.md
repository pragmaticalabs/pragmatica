### Fixed (2026-11-11 — #3044 P1b: only sequenced transport views replace the leader FSM's stored view)
- Leader election drops unsequenced wholesale `NodeAdded`/`NodeGone` view writes. A SWIM Faulty and a leader losing
  eligibility now remove exactly that node, view-free (`PeerRemovedFromView`), so neither can restore a peer the
  transport evicted. `LeaderElectionContext.setCurrentTopology` is removed.
