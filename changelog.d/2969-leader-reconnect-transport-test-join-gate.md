### Fixed (2026-11-09 — #2969: `LeaderReconnectTransportTest` flaked on a stale join, test only)
- The test gated "the join happened" on the follower's seeded topology, which already held the leader, so an eviction
  could race the transport's own `PeerJoined` and the late join cleared the suspicion the test waits for. It now waits
  for the follower manager's `PeerJoined(leader)` handler to return. No product change.
