### Fixed (2026-11-11 — #2971: a late `PeerJoined` could undo a newer eviction in the leader FSM's view)
- The QUIC transport routes each view-carrying observation (`PeerJoined`, `PeerDisconnected`, `PeerReconnected`) on the
  thread that made the peer transition, so a slow join handler could land after a newer eviction and its older view
  put the evicted leader back, clearing the follower's suspicion (a delayed re-election). Each observation now carries
  a per-transport sequence taken before the view is read; the leader FSM's context drops a view older than the last
  one applied, atomically with the write, and a stale `NodeGone` no longer suspects the leader. SWIM observations stay
  unsequenced and apply in arrival order.
