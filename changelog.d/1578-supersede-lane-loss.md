### Fixed (2026-09-28 — #1578: a lane the acceptor opened was read by nobody, so FORWARD could die silently; a late designated dial doubled the handshake)
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
- **A late core's cold start made each seed's designated dial complete late and supersede the live link** (#1554 measured 28 handshakes for 5 cores where 20 were needed). When a peer goes CONNECTED over another link, our own dial to it that has not completed its QUIC handshake is now abandoned (typed `DialAbandoned`, counted in the new `quic_dial_abandoned_total` transport metric, never reported as a connect failure). [verified: integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicDialAttemptTest.java — five in-JVM cores in the late-core shape: exactly 20]
- **A dial past its QUIC handshake is never abandoned**: its Hello may already be with the peer, which may adopt the connection. [mechanism: the client sends Hello only after the QUIC connect succeeds (`QuicClusterClient.handleQuicConnect` → `sendHello`), the acceptor registers a connection only on a Hello (`QuicClusterServer.admitHello` → `registerPeerConnection`), and an attempt leaves PENDING exactly once, to ESTABLISHED or ABANDONED, by compare-and-set] [verified: QuicDialAttemptTest `attemptPastItsQuicHandshake_isNeverAbandoned_evenWhileItsHelloIsUnanswered`]
- A dial attempt that outlives its per-attempt timeout cannot complete late: its socket is closed at the timeout and again by the next dial, so after a long outage one connection per end forms. This was already the behaviour; it is now pinned. [verified: QuicDialAttemptTest `peerDownLongerThanTheConnectTimeout_onlyTheNewestAttemptConnects_oneLaneSetEachEnd`]
- The Ember late-core bound stays [20, 28]: a seed whose dial passed its QUIC handshake before the late core's link attached still completes, and the lower-id link supersedes — at most one live attempt per seed. [unverified: the Ember count distribution after this change]
- Not fixed here: #1578's report of a designated dialer re-dialing an already-connected peer three extra times. The queued-retry explanation does not hold (timed-out attempts cannot complete late), so that mechanism is still unknown. [unverified]
