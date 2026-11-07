### Fixed (2026-11-06 — #2759: FSM2 CF-8, orphan targets)
- **A slice target whose owner blueprint was gone could survive a lost removal batch (FSM2).** When the removal batch was
  lost with its leader's term, the target stayed after the rebuild. Reconcile now removes a target whose owner blueprint is
  absent in a new step (6b), after a two-tick hysteresis. `[mechanism: Orphans.runTargets in the reconcile tick]`
- **Under ALL_OR_NOTHING, a rollback lost with its term is derived again from the permanent-failure mark, and the mark
  is scoped to the attempt it judged (FSM2).** The judgment now records the attempts it judged (`judgedAttempts`, a new
  last component of `ArtifactJudgmentValue`: a wire-layout change, same tag). **BREAKING:** bytes written in the previous shape
  no longer decode (IndexOutOfBounds standalone, ClassCastException when embedded); a cluster must not run mixed versions. A republish of a marked artifact (a fresh
  attempt) is never rolled back by the older mark, whichever of its publish batch and the judgment removal commits first.
  `[mechanism: Outcomes.rollbackMarked compares the open apply's attemptId with the mark's judgedAttempts]`
- **A permanent failure mark that a lost republish batch left behind no longer blocks placement forever (FSM2).** When the
  batch that removes the mark was lost across a term change, the new attempt was neither rolled back nor placed. The
  leader now removes a permanent mark once an open apply carries an attempt the mark did not judge, with a removal that
  expects the observed judgment value (a newer mark is never removed); it is derived again on each tick until it lands.
  `[mechanism: Outcomes.supersededMarks in the reconcile sweep, exact-value-guarded removal]`
