### Fixed (2026-11-06 — #2759: FSM2 CF-8, orphan targets)
- **A slice target whose owner blueprint was gone could survive a lost removal batch (FSM2).** When the removal batch was
  lost with its leader's term, the target stayed after the rebuild. Reconcile now removes a target whose owner blueprint is
  absent in a new step (6b), after a two-tick hysteresis. `[mechanism: Orphans.runTargets in the reconcile tick]`
