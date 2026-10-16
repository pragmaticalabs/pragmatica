### Fixed (2026-10-16 — DurableProjectionRebuildTest zombie-checkpoint test raced a live consumer)
- **`zombieCheckpoint_atTheOldEpoch_isRefusedByTheApplier` was racy.** The rewind's own apply restarts the setUp
  consumer, whose async catch-up checkpoint (offset 0, rewound epoch) is accepted by the applier (same epoch,
  lower offset) and could land after the test's put of 3. Test-only: the consumer is now detached before the test
  drives the applier. [verified: with a 300 ms fixture delay on the rewound commit the old test went RED and the
  fixed test stays GREEN; `aether/node/src/test/java/org/pragmatica/aether/node/projection/DurableProjectionRebuildTest.java`]
