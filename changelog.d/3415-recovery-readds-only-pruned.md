### Fixed (2026-11-19 — #3415: a worker whose drain is refuted was added to the DHT ring of every core)
- **After a refuted drain of a worker, cores counted the worker among the owners of DHT keys.** A worker that went
  DEPARTING and then back to MEMBER (a drain it did not act on) was added to the consistent-hash ring of every core
  that saw the edge, although a worker holds no DHT data: writes and repair rounds could be addressed to it, and the
  keys it was counted for were short of one real copy without any core knowing. [design intent — unverified: the
  data consequence was traced in the code, not run on a cluster]
- A core now restores on that recovery edge only the node it took off its own ring on the departing edge; a node
  that was not on the ring (a worker) leaves the ring unchanged, and a removal decision ends the restore.
  [mechanism: `DHTTopologyListener` records a prune only when the node was a ring member, inside the same ring
  change that removes it; pinned at unit level by `DHTTopologyListenerDepartingTest`]
- A core member that first reaches MEMBER through a refuted drain still joins the ring through its join decision.
  [mechanism: the first MEMBER entry emits the JOINED edge, also when it is reached by recovery; pinned at unit level by
  `MembershipFsmTest`]
- Operator recovery for a cluster already affected: restart the affected cores; a restarted core builds its ring
  from the configured cores and the join decisions it observes, so the worker is not on it.
  [design intent — unverified]
