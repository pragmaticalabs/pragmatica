### Fixed (2026-11-24 — #3686: `QuicLaneBacklogTest` held-frame assertions raced the give-up)
- **A test could find its held frame already given up.** `QuicLaneBacklogTest` built most of its networks with a held-frame
  budget of 50 ms to 15 s, so on a loaded host the give-up removed a frame before the assertion that it was held. Every test
  whose subject is not the give-up now uses `LossAccountingFixtures.networkWithoutGiveUp` (a one-hour budget); the tests
  whose subject is the give-up assert nothing it can undo. Test-only: no production change.
  `[verified: QuicLaneBacklogTest#episode_entryWarnThrowsOnTheFirstHeldFrame_theFrameIsStillDrained_andNoEpisodeStaysOpen with a 250 ms stall before the assertion]`
