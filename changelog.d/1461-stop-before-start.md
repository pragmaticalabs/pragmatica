### Fixed (2026-09-30 — #1461: a stop before start left an unstoppable QUIC transport)
- **`QuicClusterNetwork.stop()` before `start()` was a silent no-op**, and the later start then armed a server, the
  missing-peer reconciler and the keepalive, none of which anything would stop. A stop is now recorded whether or
  not the network had started, and a stopped network stays stopped: a later start refuses with the terminal
  `NETWORK_STOPPED` and binds nothing. A stop racing an in-flight start is also caught after the server and client
  are created, and the periodic tasks are armed only while the network is open.
- Behaviour change: restarting a `QuicClusterNetwork` instance after `stop()` is refused; create a new one.
  Certificate rotation is not a stop and is unaffected.
  [verified: `integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicClusterNetworkLifecycleTest.java`]
