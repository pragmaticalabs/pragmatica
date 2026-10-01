### Fixed (2026-10-01 — #1798: `RabiaReorderedDeliveryTest` settles before reading the phase)

- **Two phase assertions now settle before reading the phase (defensive; the CI red they were prompted by is unexplained).**
  `decisionPastAMissingSlotAppliesInOrderWithoutResync` and `replicaThatMissedASlotCatchesUpFromTheNextDecision`
  assert the engine phase straight after a commands-applied pump; both now `cluster.settle()` first. A CI red
  (`Phase[2]` expected, `Phase[1]` actual) prompted it, but the commit-to-advance window was refuted as its cause by
  sleep injection (0 of 1,260 red in each arm), so this does not claim to fix it. Test-only; removing the
  `advancePhase` call in `commitDecision` still reddens the class (37 of 42), so the settle cannot mask a phase
  that never advances.
