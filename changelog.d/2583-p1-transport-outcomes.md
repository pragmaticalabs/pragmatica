### Changed (2026-11-02 — #2583 P1: unicast transport sends report their local outcome)
- **Every unicast cluster-transport send now returns `Promise<WriteOutcome>`** instead of `Unit` or `void`:
  `ClusterNetwork.send` (both overloads), `DHTNetwork.send`, `ReplicationTransport.send` and `StreamForwardTransport.send` (both overloads). The
  outcome is local only: `Sent` means the transport accepted the frame, and only a refusal (`BackpressureRefused`,
  `NoPeerState`, `EncodeFailed`, or `ConnectionDead` for a `NoOfflineBuffering` frame) proves it never left.
- **Breaking:** `ClusterNetwork.sendOutcome` and `DHTNetwork.sendOutcome` are removed (their bodies are now `send`),
  and so are the defaults that reported `Sent` for every refusal class. `ClusterNetwork.send(NodeId, M, TimeSpan)` is
  abstract: a transport without an offline buffer implements it as plain `send`. Callers that read the outcome
  (`HttpForwarder`, `EntityForwardService`, `DistributedDHTClient`, `DHTAntiEntropy`, `StreamForwardClient`) moved to `send`.
- **No caller changes what it does with an outcome in this release step.** The QUIC `send` body is the former
  `sendOutcome` body, and the two dispatch paths differed only in the return value
  `[mechanism: QuicClusterNetwork.send → dispatchPayloadWithOutcome, the sole remaining dispatch]`.
- A ratchet pins the rest: `TransportOutcomeDisciplineTest` fails when a `src/main` transport send discards its
  outcome without an allow-list entry citing what compensates for a lost frame or the #2583 phase that adds a policy
  `[mechanism: a lexical source scan of the seven modules that name a transport interface; the scan asserts its own
  search space]`.
