### Fixed (2026-09-28 — #1610: tcp `Server.stop()` resolved before the server had stopped)
- **`Server.stop()` could resolve while the server was still running.** Shutting down the event loop groups and
  resolving the caller's promise were two independent `onResult` actions with no mutual order, and
  `shutdownGracefully()` only starts the shutdown. So a caller could see `stop()` complete while the loops were
  still running and the TCP listen port was still bound. Rebinding the same port right after `stop()` failed
  with `BindException: Address already in use` in 5 of 5 runs at rc4.
- `stop()` is now one dependent chain: close the TCP and UDP channels, run the intermediate operation, then shut
  down both groups and wait for them to terminate. It resolves with the intermediate operation's outcome, or
  with the shutdown failure if the groups do not terminate. Each channel close is bounded to 2 s and each
  group's termination to 6 s from the caller's side, because a wedged loop cannot enforce Netty's own timeout.
  So a wedged boss or UDP loop no longer hangs `stop()`: the groups are still asked to shut down, and `stop()`
  fails with `CoreError.Timeout` (#1614). An intermediate operation that never resolves still hangs `stop()`,
  and that bound is the caller's. The groups are shut down with no quiet period: in Netty 4.2.9 the loop
  closes every registered channel (`NioIoHandler.prepareToDestroy`) before the quiet period is consulted, so
  the period never protected in-flight writes and would only delay termination. **`stop()` can now fail**, with the group
  shutdown's cause, where it always used to succeed; a caller in a shutdown sequence must not abort on it. No aether
  production path calls it today (every `ClusterNetwork.server()` returns empty), so this reaches only external users
  of the library.
  [verified: `integrations/net/tcp/src/test/java/org/pragmatica/net/tcp/ServerStopTest.java` — a held worker task
  keeps `stop()` unresolved until the loops terminate, the same TCP+UDP port rebinds right after `stop()`, and
  a wedged boss or UDP loop makes `stop()` fail within its bound with both groups asked to shut down]
