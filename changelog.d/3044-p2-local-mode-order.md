### Fixed (2026-11-12 — #3044 P2: local-mode leader adoption follows the view fence)
- In local mode (no consensus), a transport disconnect is applied to the stored view first, through the same sequence
  fence the FSM uses; only an applied one adopts the leader, from the STORED view, before the `NodeGone` is
  dispatched. A stale eviction routed after a newer reconnect no longer moves the leader. A SWIM Faulty removes the
  peer first and adopts from the stored view, never from SWIM's snapshot. A fresh eviction of the leader still moves
  it with no intermediate "no leader" notification.
