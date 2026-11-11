### Fixed (2026-11-11 — #3033: a simultaneous dial could leave one end unconnected)
- **Evicting an inbound link closed the UDP socket of this end's own dial of the same peer.** When two nodes dial each
  other at once, the peer closes the loser link and this end evicts it; the eviction released the per-peer client
  datagram channel by peer id alone, which is the socket of this end's own dial, already past its QUIC handshake.
  That dial then attached a dead connection, was evicted a millisecond later, and the peer stayed unconnected here
  until the reconciler's backoff expired (`QuicSimultaneousDialLaneTest` timed out after 15 s on `both ends CONNECTED`).
  `QuicClusterClient.closeDatagramChannelOf(peer, link)` now releases the channel only when the evicted link runs over
  it; peer departure and the hung-dial eviction keep the unscoped close.
- Pinned by `QuicEvictedInboundKeepsPendingDialTest` and two `QuicClusterClientReconnectTest` cases.
