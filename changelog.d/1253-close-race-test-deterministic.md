### Fixed (2026-09-19 — #1253: OffHeapRingBufferCloseRaceTest flakes under CPU load)
- **The close-under-readers race test could never enter its race window under CPU load.** Its
  non-vacuity check (the race window WAS entered) relied on the scheduler to put a reader between the
  `closed` flag check and the native read at the moment `close()` landed. Readers that counted down
  and were then descheduled let `close()` land with no read in flight. Reproduced in 4 of 40 fresh-JVM
  runs under a 32-thread CPU hog on an 8-core box.
- `OffHeapRingBuffer` gains a package-private test seam, `readWindowProbe`. `guardedRead` runs it
  after the `closed` check and before the native read, and production code never sets it (the default
  is a no-op). The test uses it to park one reader in that window until `close()` has completed, so
  every round enters the window by construction. The non-vacuity check tightens from "more than zero
  refusals in total" to "at least one refusal per round". The safety check (no throwable escapes a
  reader) is unchanged. [verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/OffHeapRingBufferCloseRaceTest.java]
  This is a unit test, not a live-path test. It passed 40 of 40 under the same hog. It still fails
  when the `guardedRead` catch is removed.
