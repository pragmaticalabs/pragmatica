### Fixed (2026-09-30 — #1489: quic_handshake_failures_total counted every failed dial)
- **Every failed dial incremented `quic_handshake_failures_total`**, whatever the cause, so the metric measured churn
  and the 12-network TLS contract (C3) was uncheckable. A QUIC connect that fails in its TLS handshake is now its
  own cause (`QuicTransportError.HandshakeFailed`, recognised by an `SSLException` in the connect failure) and the
  only thing counted by `quic_handshake_failures_total`. The new `quic_dial_failures_total` counts every failed dial.
  [verified: `integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicDialFailureMetricsTest.java`
  — a foreign-cluster peer (TLS refusal) and an identity mismatch (TLS completed), real QUIC endpoints in one JVM]
- A server that refuses OUR certificate closes the connection after our side of the TLS handshake completed; that
  dial counts as a dial failure, not a handshake failure. [unverified: whether that close carries a TLS alert the
  client could attribute]
