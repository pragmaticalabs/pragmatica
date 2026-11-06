### Fixed (2026-11-06 — #2692: `SegmentFallbackTest` read an evicted offset while its seal was still in flight)
- **Test-only.** `SegmentFallbackTest` waited for the segment index alone, but the sealer indexes a segment before it drops
  its retained copy, and a read in that gap is refused with the designed transient `SealInFlight`. Under reactor load the
  corrupted-ring test (and the evicted/mixed-range tests) saw `SealInFlight` instead of the expected result. The wait now
  requires the offset to be indexed AND released (`SealReleaseLag.awaitReadable`), and the sealer's sink lags its release
  so the gap is deterministic. No product behaviour changed.
