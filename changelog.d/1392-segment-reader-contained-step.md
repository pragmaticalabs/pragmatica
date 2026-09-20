### Fixed (2026-09-20 — #1392: rc4 tip red: StackOverflowError in SingleFlightCache.boundedLoad when a durable-entity seal lands inside the retry window)
- **A step that throws inside the iterative sealed-segment read is a failed step.** #1394's loop ran
  each step on the caller's frame (inline) or inside `onResult`'s consumer (after an off-thread
  resume), so a synchronous throw from the ref lookup — the one call outside the step's own
  `flatMap`s — escaped `readEvents` as an exception on the first path and was logged and dropped on
  the second, leaving the read never settled; the `flatMap`-per-segment reader had turned it into a
  failed promise. Each step now goes through `Result.lift`, so the read fails exactly once on both
  paths with the throw as its cause. `[mechanism: only `MetadataStore.resolveRef` can throw there and
  the sole implementation cannot; this restores the contract for any future store]`
  [verified: `SegmentReaderTest$StackDepthAcrossSegments.readEvents_failsTheRead_whenARefLookupThrowsOnTheInlinePath`
  (red on #1394: `IllegalStateException` escapes) and `…whenARefLookupThrowsAfterAnOffThreadResume`
  (red on #1394: the read times out, never settled)]
- **The off-thread reader pins await with a budget, and one crosses `maxEvents` mid-segment after a
  resume.** An ignored-failure mutation used to park the suite indefinitely; it now fails in seconds.
  The resume path's `remaining` bookkeeping was unpinned — `from=4, max=4` over 3-record segments
  returns exactly `[4..7]`; an un-decremented `remaining` returns `[4..8]`. The limit must land inside
  a segment, not on its end, or the bounded ref range pins nothing.
  [verified: `readEvents_stopsAtMaxEvents_whenTheLimitFallsMidSegmentAfterAnOffThreadResume`, red
  under the `remaining`-unchanged mutation; `readEvents_failsTheWholeRead_whenAGetSettlingOffThreadFails`
  red in 21 s under the ignored-failure mutation]
