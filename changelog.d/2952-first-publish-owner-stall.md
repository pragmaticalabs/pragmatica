### Fixed (2026-11-09 — #2952: a first publish forwarded to a stream's owner could time out as `503 outcome unknown`)
- **Two waits on the owner's threads delayed a forwarded first publish past the sender's 2.5 s hop.** The node that
  applies a stream's committed config walked the whole WAL root on the KV dispatcher (`raiseOrphanedLives`), which held
  every config apply behind it. And an owner that received the forwarded publish before its config applied waited up
  to 2 s for its own config `Put` on the transport's event loop, so every other forward to that owner queued behind it.
- The stream-life reports (other lives of a name, orphaned WAL directories) now run on their own virtual thread, off
  the KV dispatcher, and a node's own lives are never reported as orphans.
  [verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/OrphanedWalDirectoryTest.java]
- The forwarded publish now answers from a promise: the bounded wait for the config commit and the owner append's
  group-commit fsync are composed rather than awaited, so the event loop serves other forwards meanwhile. A commit
  slower than the 2 s bound still answers a retryable refusal, and a destroy issued after the wait elapsed stays
  queued behind the in-flight `Put`.
  [verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/ForwardedPublishEventLoopTest.java]
  (in-JVM, single owner; the multi-node path is covered by
  `aether/ember/src/test/java/org/pragmatica/aether/ember/EmberFirstPublishRefusedCatchupTest.java`).
- Not changed: the management publish route and the control log still materialize a stream with a blocking wait of
  at most 2 s; neither runs on the stream forward event loop (the control log runs on its own virtual thread; the
  management route's thread is [design intent — unverified] whether it may park).
