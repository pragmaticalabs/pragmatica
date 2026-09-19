### Fixed (2026-09-19 — #1247: OffHeapRingBuffer reports index-corruption bugs as BUFFER_CLOSED)
- **`OffHeapRingBuffer.guardedAccess` and `guardedRead` caught `IndexOutOfBoundsException` together with
  the closed-arena `IllegalStateException` and handled both as a close.** The `Result` paths returned
  `BUFFER_CLOSED`, and the header and retention paths counted the fault as a closed-under-reader race.
  An out-of-bounds native access means the ring's index or offset arithmetic is wrong, so a corrupted
  ring was reported as a benign release with no log.
- `IllegalStateException` keeps its `BUFFER_CLOSED` / closed-under-reader handling. `IndexOutOfBoundsException`
  is now logged at ERROR with stream and partition, counted by the new `indexCorruptionCount()`, and, on the
  `Result` paths, returned as the new `StreamError.RingIndexCorrupted` cause.
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/OffHeapRingBufferIndexCorruptionTest.java — a real index entry is corrupted; unit level]`
- The two comments claiming "the single justified try/catch" in a file that has four now name all four
  marked sites.
- **The cause now reaches the caller on the segment-fallback read path too.** After a `CursorExpired` fallback to
  sealed segments, `PartitionedStreamAccess` read the ring tail with `.or(List.of())`, so a corrupted ring
  silently truncated the read to the sealed events. `RingIndexCorrupted` now propagates. Other buffer failures
  keep the short-read degrade that path always had.
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/SegmentFallbackTest.java — CorruptedRingAfterFallback]`
