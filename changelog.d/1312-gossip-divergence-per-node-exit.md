### Fixed (2026-09-29 — #1312: GossipKeyDivergenceGuard called System.exit, so in Ember/Forge one node's divergence killed every in-JVM node)
- **The SWIM gossip-key divergence gate (#683) exited with a hard-coded `System.exit(1)`.** Every other node exit
  (drain, identity refusal, SWIM start failure) goes through a per-node hook that an in-JVM host (Ember/Forge)
  implements as "stop this node". This one bypassed it, so a divergence on one simulated node took down the whole
  JVM and every co-hosted node with it.
  The gate now exits through a per-node `gossipKeyDivergedExit`, threaded from the `AetherNode` factories to
  `startSwim`. Exit code `AetherNode.EXIT_GOSSIP_KEY_DIVERGED` = 1.
  [mechanism: `startSwim` builds the guard with the hook it is handed; pinned by
  `AetherNodeStartSwimTest.startSwim_gossipKeyDiverges_exitsThroughTheNodesDivergenceHook`. That test drives a real
  detector on a real UDP port with real datagrams under a rotated key. It goes red if the guard is routed to
  `failNode` or to a no-op, or if the gate ignores its clock seam.]
- **Production is unchanged:** `AetherNode.aetherNode(config)` passes `System.exit(1)`, not `halt`, so the node's
  shutdown hooks still run. The gate stays armed for minutes after boot, long enough for the node to have joined
  consensus over QUIC. #683 measured `System.exit` terminating cleanly from this thread, and `Main.shutdownNode`
  bounds the hooks with `halt(3)`. In-JVM hosts record code 1 and stop only that node.
  [design intent — unverified: no in-JVM multi-node run with one diverging node]
