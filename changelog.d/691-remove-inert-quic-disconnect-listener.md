### Removed (2026-09-14 — #691: `QuicDisconnectListener` was inert — its only consumer went with the HealthSignal bus)
- **Decision: remove.** `QuicClusterNetwork.setDisconnectListener` had no caller since #571 deleted
  `AetherNode.attachQuicDisconnectListener` (`git log -S'setDisconnectListener'` over origin and tags: the
  only history is the original transport overhaul and the node's initial wiring, whose listener body was one
  emit into the deleted sink — so it was inert before #571 too). The leader-side
  `disconnectListener.onDisconnect(peerId)` ran against `noop()` on every teardown. The live teardown signal is
  `PeerConnectivityReporter.onPeerDisconnected`, which `reportPeerRemoval` fires on leader and follower alike
  and which `AetherNode.attachQuicConnectivityReporter` folds into the local `PeerObservationBuffer`
  (the leader's direct `ReachabilityAggregator` ingest, Step 4 of the topology-observation refactor). A
  "leader fast-path disconnect reaction" would be a second consumer of the same event with a design of its
  own (observability-first); nothing today needs it, so the interface, the field, the setter, the
  constructor parameter and the leader gate that existed only to guard the listener
  (`isLeaderSupplier` in `QuicClusterNetwork`, the first argument of `setFollowerObservationWiring`, and the
  matching `attachQuicConnectivityReporter` parameter in `AetherNode`) are gone. `QuicPeerStateListener`
  (join/reconnect/leave hints for CTM) is untouched.
  [mechanism: enforced by the compiler — `QuicClusterNetwork` constructors lose the parameter, `setFollowerObservationWiring`
  loses its leader gate; `git grep 'QuicDisconnectListener|setDisconnectListener|disconnectListener'` over
  `*.java` returns only the two history comments; root `mvn clean install -DskipTests` 145/145]
  [verified: `QuicClusterNetworkHintEmissionTest#disconnect_unknownPeer_emitsConnectivityObservation_withoutAPriorLink`
  — the REMOVE view-change for a peer that never had a QUIC link still reports through the connectivity
  reporter (the property the deleted listener test carried, now on the surviving path);
  `QuicClusterNetworkLivenessSweepTest` still pins the death-path/organic-drop split the reporter carries]
- The two hint-emission tests that pinned the listener's leader-only firing are retired with it; the test
  that pinned "follower skips the listener" became the reporter test above.
- [unverified: no cluster run — the removed arm was a no-op on every node, so no runtime behaviour changes;
  inferred from the `noop()` default and the absent setter call, not observed]
