### Fixed (2026-09-30 — #1366: a QUIC dial after stop() dereferenced the nulled client)
- **`QuicClusterNetwork.stop()` nulls its client, and the dial path read the field twice**: at the resolve step and
  in the resolve-success continuation, which a stop can overtake. A dial after stop threw an NPE (surfaced by
  #1311's escape guard). Both steps now read the client once; a stopped network does not dial, and leaves no peer
  CONNECTING. A stop racing past the read closes the captured client, which fails that dial through the ordinary
  failure path.
  [verified: `integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicClusterNetworkLifecycleTest.java`]
