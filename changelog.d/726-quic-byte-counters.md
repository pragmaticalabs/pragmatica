### Added (2026-09-04 — #726: honest QUIC payload-byte counters)

- Added `quic_bytes_sent_total` / `quic_bytes_received_total` to `QuicTransportMetrics`, counting
  PAYLOAD bytes at the lane boundary — the serialized frame handed to the channel on send, and the
  frame decoded from the buffer on receive — never QUIC framing, TLS encryption overhead, or
  retransmits, and never a bandwidth figure.
  [mechanism: `QuicClusterNetwork#writeIfWritable` and `#rawBackpressuredWrite` call
  `onBytesSent(byte[].length)` on the outbound path; `QuicLaneDataHandler#channelRead0` calls
  `onBytesReceived(byte[].length)` on the inbound path — both new instrumentation points on the
  live transport, replacing the dead handler removed alongside this change]
- Threaded a `QuicTransportMetrics` instance through the `QuicClusterServer` / `QuicClusterClient`
  / `QuicClusterNetwork` factory signatures and `QuicLaneDataHandler`, so the counters are sourced
  from the transport code path that actually carries traffic.
  [verified: `integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicClusterNetworkStreamZombieTest.java`
  `EndToEndReconnectHandshake#acceptorHandshakeThenWrite_peerStaysConnected_writeSucceeds` — two
  real `QuicClusterNetwork` nodes, live handshake plus keepalive exchange, byte counters confirmed
  positive at both lane-boundary directions for both the dialer and the acceptor]
- Red-before-green: reverting the two send hooks and the one receive hook makes the above test's
  byte-counter assertions fail with a genuine `AssertionError` (not a compile error); restoring
  them verbatim makes it pass again.
  [verified: same test as above]
- Exposed both counters on `GET /api/v1/metrics/transport` and as Prometheus gauges, with
  documentation and gauge help text stating the payload-boundary/no-bandwidth semantics explicitly.
  [verified: `aether/docs/reference/management-api.md`;
  `aether/aether-metrics/src/main/java/org/pragmatica/aether/metrics/observability/ObservabilityRegistry.java`]
