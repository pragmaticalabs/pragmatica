### Fixed (2026-10-01 — #1798: test-only race in `RabiaReorderedDeliveryTest`)

- **Two phase assertions read the engine phase before the task that advances it had finished.** The wait
  predicate is "commands applied", which holds inside the same executor task that then calls `advancePhase`,
  so `decisionPastAMissingSlotAppliesInOrderWithoutResync` and `replicaThatMissedASlotCatchesUpFromTheNextDecision`
  could read the phase one slot behind (seen once in CI as `Phase[2]` expected, `Phase[1]` actual). Both now
  `cluster.settle()` first. Test-only; no production change. Removing the `advancePhase` call in
  `commitDecision` still reddens the class (37 of 42), so the settle does not mask a phase that never advances.
