### Fixed (2026-10-15 — SWIM stop-before-start left a zombie detector bound to its UDP port)
- **A `stop()` that reached the SWIM health detector before its first `start()` was lost.** `Stopped` is both the
  initial and the post-stop state and ignored `StopRequested`, so the later `StartRequested` moved to `Starting`,
  bound the SWIM UDP port, reached `Running` and nothing ever stopped it. It bites in-JVM hosts (Forge, Ember);
  under `Main` process exit releases the socket.
- The early stop is now terminal: `Stopped` carries a `closed` latch, a stop of an open `Stopped` closes it, and a
  later `StartRequested` is refused with a WARN. A late `ProtocolReady` from an already-launched start is still
  closed as an orphan (#1308). Stopping a Running or Starting detector lands in the open `Stopped`, so ordinary
  stop/start restart is unchanged; the only production start site is `AetherNode.startSwim`.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/health/CoreSwimHealthDetectorStartLeakTest.java`,
  `aether/node/src/test/java/org/pragmatica/aether/node/health/fsm/SwimHealthFsmTest.java`]
- `CoreSwimHealthDetector.start()` short-circuits on a closed detector: no transport is built, no port is bound, and
  it resolves as a logged no-op success. It deliberately does not fail: `AetherNode.startSwim` runs `failNode` on a
  start failure (production: halt; Ember: a stale one could stop a same-id relaunch). `startSwim` skips the join
  announcement when the detector is closed.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/AetherNodeStartSwimTest.java`]
