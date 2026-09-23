### Fixed (2026-09-23 — #1456: a listener bound after `stop()` ran was never stopped and held its port forever)

- **`stop()` was not ordered against an in-flight `start()`.** Every listener in the node published
  itself into a field *after* its asynchronous bind completed, while `stop()` released whatever that
  field happened to hold at the moment it ran. When `stop()` landed in the window between the bind
  being issued and the field being written, it released nothing, reported success, and the bind then
  completed onto a listener nobody owned — unreachable, and holding its port for the life of the
  process. Observed in CI as `EmberClusterSwimStartFailureTest` failing on
  `TCP <mgmt port> must be reclaimable within 5000 ms`, on three runs across branches sharing no
  files, with one node's management, app-http and QUIC ports all held at once.
- **The fix publishes against a terminal `Closed` state instead of into a bare field**, so the
  publish and the close are decided at one atomic location and exactly one of the two callers is
  handed the listener to release. A bind that loses the race is closed by its own publisher.
  `stop()` still never waits for `start()` — #1308 established that a start can stay pending
  indefinitely when quorum cannot form, and escaping that is precisely what the abort path is for.
- Sites fixed: `ManagementServer` HTTP/1.1 and HTTP/3, `AppHttpServer` HTTP/1.1 and HTTP/3 (via
  `AppHttpState.Stopped`, which previously ignored `H1Ready`/`H3Ready` outright), and
  `QuicClusterServer`'s cluster UDP channel. A bind that loses the race in the QUIC transport now
  fails its start with the new terminal `QuicTransportError.General.STOPPED_DURING_START`, which also
  keeps `QuicClusterNetwork`'s post-start reconciler and keepalive schedules from arming a transport
  that was already stopped — a leaked reconciler was seen still dialling 2m10s later, inside
  unrelated test classes. The SWIM detector already handled this case (#1308) and is unchanged.
- New: `org.pragmatica.lang.concurrent.PublishSlot`, the three-state publish/close slot the fix is
  built on, with `take()` for certificate rotation (replace the listener without closing the slot).
