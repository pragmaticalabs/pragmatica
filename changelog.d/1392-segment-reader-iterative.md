### Fixed (2026-09-20 — #1392: rc4 tip red: StackOverflowError in SingleFlightCache.boundedLoad when a durable-entity seal lands inside the retry window)
- **A read across many sealed segments nested the stack once per segment.** `SegmentReader` chained
  one `flatMap` per segment; a memory tier settles `get` synchronously and `SingleFlightCache` hands
  that back already resolved, so every continuation ran inline — 7 frames per segment. The sealer
  seals one evicted record per segment, so a 512-record entity replay batch over one-record segments
  was ~3,600 frames deep, and on CI's 1 MB thread stack it overflowed about 480 segments in, inside the
  per-segment `.timeout()` cancel while the virtual thread parked on a contended scheduler lock. #1362's
  containment then reported the `StackOverflowError` as a failed fold. The ticket's reading — a fold
  retry re-entering `compute` synchronously — is not the mechanism: `Retry` always re-schedules an
  attempt on a fresh thread. `[mechanism: CI trace of run 35517545624 — one `replaceResult` escape and
  485 nested `fold` escapes, one per `readNextSegment` level; a depth probe measures the same 7 frames
  per `storage.get` at `edcf8f4c0` and at the tip, so no rc4 squash changed it]`
- **The sealed-segment read is iterative.** A step that is already settled is consumed in place and the
  loop moves to the next segment; only a step still pending suspends the loop, which resumes on the
  thread that settles it. Stack depth no longer depends on the segment count — the reader-side twin of
  the sealer's #1234 drain trampoline.
  [verified: `SegmentReaderTest$StackDepthAcrossSegments.readEvents_keepsStackDepthFlat_acrossTwoThousandSynchronouslySettlingSegments`
  — measured growth 0 frames across 2,000 one-record segments; on the previous reader the same read
  overflows; `…whenEachGetSettlesOffThread` and `…whenAGetSettlingOffThreadFails` pin the suspended
  branch]
  `[unverified: CI red not reproduced locally — a 2 MB AArch64 default stack passes the entity test at
  every rc4 commit, including at -Xss1024k; CI on the fix is the arbiter]`
