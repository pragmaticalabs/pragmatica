### Added (2026-09-04 — #726: honest QUIC payload-byte counters)

- Added `quic_bytes_sent_total` / `quic_bytes_received_total` to `QuicTransportMetrics`, counting
  PAYLOAD bytes at the lane boundary — the serialized frame handed to the channel on send, and the
  frame decoded from the buffer on receive — never QUIC framing, TLS encryption overhead, or
  retransmits, and never a bandwidth figure.
  [mechanism: hooked at all nine payload-byte-moving sites on the transport: the two data-path
  hooks (`QuicClusterNetwork#writeIfWritable` / `#rawBackpressuredWrite` on send,
  `QuicLaneDataHandler#channelRead0` on receive) plus the handshake and lane-preamble paths in
  `QuicClusterClient` (`sendHello`'s preamble+Hello writes, `handleDataLaneCreated`'s and
  `completeLazyLaneOpen`'s lane-preamble writes, `ClientHelloHandler#channelRead0`'s Hello-response
  receive) and `QuicClusterServer` (`sendHelloResponse`'s write, `completeLaneOpen`'s lane-preamble
  write, `ServerStreamHandler#channelRead0`'s combined preamble+Hello receive) — no new plumbing
  needed anywhere, since `QuicTransportMetrics` was already a field reachable from every site,
  directly or via the enclosing instance from a non-static inner class]
- Threaded a `QuicTransportMetrics` instance through the `QuicClusterServer` / `QuicClusterClient`
  / `QuicClusterNetwork` factory signatures and `QuicLaneDataHandler`, so the counters are sourced
  from the transport code path that actually carries traffic.
  [verified: `integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicClusterNetworkStreamZombieTest.java`
  `EndToEndReconnectHandshake#acceptorHandshakeThenWrite_peerStaysConnected_writeSucceeds` — two
  real `QuicClusterNetwork` nodes, live handshake plus keepalive exchange, byte counters confirmed
  positive at both lane-boundary directions for both the dialer and the acceptor]
- The same test now also asserts both counters are already positive immediately after the Hello
  handshake completes, strictly before either node's keepalive beacon (the first
  application-level write) fires — proving the claim covers handshake and lane-preamble bytes,
  not only post-handshake application traffic.
  [verified: same test as above]
- Red-before-green: reverting the nine hooks above makes the handshake-phase assertions fail with a
  genuine `AssertionError` (a real 10s poll timeout, not a compile error and not a silent pass) —
  restoring the hooks verbatim makes it pass again. The probe is only genuine because
  `EndToEndReconnectHandshake` also stretches its `pingInterval` stub to 30s: at the pre-existing 1s
  cadence, the transport's automatic keepalive scheduler independently satisfies the same
  "byte counter is positive" condition via unrelated, already-hooked code paths inside the 10s poll
  window, so the assertion passed even with all nine hooks reverted — caught by build-runner's own
  mutation-probe check, not assumed.
  [verified: same test as above]
- Exposed both counters on `GET /api/v1/metrics/transport` and as Prometheus gauges, with
  documentation and gauge help text stating the payload-boundary/no-bandwidth semantics explicitly.
  [verified: `aether/docs/reference/management-api.md`;
  `aether/aether-metrics/src/main/java/org/pragmatica/aether/metrics/observability/ObservabilityRegistry.java`]
