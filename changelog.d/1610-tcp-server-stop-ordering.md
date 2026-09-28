### Fixed (2026-09-28 — #1610: tcp `Server.stop()` resolved before the server had stopped)
- **`Server.stop()` could resolve while the server was still running.** Shutting down the event loop groups and
  resolving the caller's promise were two independent `onResult` actions with no mutual order, and
  `shutdownGracefully()` only starts the shutdown. So a caller could see `stop()` complete while the loops were
  still running and the TCP listen port was still bound. Rebinding the same port right after `stop()` failed
  with `BindException: Address already in use` in 5 of 5 runs at rc4.
- `stop()` is now one dependent chain: close the TCP and UDP channels, run the intermediate operation, then shut
  down both groups and wait for them to terminate. It resolves with the intermediate operation's outcome, or
  with the shutdown failure if the groups do not terminate. The groups are shut down with no quiet period
  (the intermediate operation is the drain, as #929 did for SWIM), and each wait is bounded to 6 s from the
  caller's side, because a wedged loop cannot enforce Netty's own timeout.
  [verified: `integrations/net/tcp/src/test/java/org/pragmatica/net/tcp/ServerStopTest.java` — a held worker task
  keeps `stop()` unresolved until the loops terminate, and the same TCP+UDP port rebinds right after `stop()`]
