### Fixed (2026-09-28 — #1578: a lane the acceptor opened was read by nobody, so FORWARD could die silently)
- **The dialer had no handler for streams the ACCEPTOR opens.** The acceptor lazily opens a data lane that
  is missing on its side (a write racing the dialer's preamble frames, which a supersede puts under live
  traffic). When that stand-in registered after the dialer's own stream for the lane, it displaced and
  closed the dialer's stream, and the acceptor then wrote the lane into a stream nothing read — every write
  reported success. FORWARD showed it first because its traffic is one-directional: forwarded requests timed
  out with no transport failure anywhere (#1554's red CI run).
  [mechanism: `QuicClusterClient` built its QUIC channel with no `streamHandler`, while `QuicClusterServer` had one]
- The dialer now reads acceptor-opened streams through the acceptor's own preamble routing
  (`PeerOpenedLaneRouter`), so every lane stream has a reader at both ends.
  [verified: `integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicLaneOwnershipTest.java`]
- **Lane ownership is decided by stream id, identically at both ends.** A dialer-opened stream outranks an
  acceptor-opened one, and between two streams from the same side the newer wins. Both ends see the same
  ids, so they keep the SAME stream without negotiating; arrival order, which differs between the ends,
  no longer decides. [verified: `integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicLaneOwnershipTest.java`]
- **A displaced stream is finished, not closed.** Its opener queues a FIN behind the writes already on it,
  so those writes are delivered; the other end closes it when the FIN arrives, returning the stream credit.
  A write that raced the retirement and failed is re-sent once on the stream the lane kept, counted by
  `QuicTransportMetrics.retiredStreamResendCount`. A message lost any other way still surfaces only as a
  write-failure log and counter; a forwarded request recovers by its hop timeout and retry.
  [verified: `integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicLaneOwnershipTest.java`]
- The transport logs a dial journal at INFO: each dial attempt is numbered, and every attach names the
  attempt or inbound Hello it came from, the phase it found and what it did.
