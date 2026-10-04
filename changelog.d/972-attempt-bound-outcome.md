### Fixed (2026-10-04 — #972: a deployment outcome is bound to the publish attempt it closes)
- **A publish that lost the apply-start race kept reporting the previous attempt's terminal.**
  `BlueprintService.confirmOutcomeStart` retries the fenced IN_PROGRESS write five times. A terminal for
  the PREVIOUS apply of the same id could win all five, and the live blueprint then carried that
  attempt's FAILED or ROLLED_BACK. The gate read it as this apply's own, so the completion repair never
  wrote SUCCEEDED after a failover and retry exhaustion never settled, and the status route reported a
  healthy deployment as the earlier failure.
- Every publish now stamps a fresh `attemptId` into `AppBlueprintValue` and its apply-start record.
  Every terminal the FSM writes carries the attempt it closes. `applyNotYetTerminal` reads a terminal
  for a different attempt than the committed blueprint's as "this apply is not yet terminal", so the
  apply still writes a terminal of its own either way.
  Pinned by `AttemptBoundOutcomeTest.java` (unit, real fenced `KVStore`); [design intent — unverified] on a live cluster.
- A terminal for the committed attempt is untouched. The repair still never overwrites a genuine
  FAILED, whatever the slices report. Pinned by `AttemptBoundOutcomeTest.control_aFailedForTheCommittedAttempt_isNotOverwrittenByTheRepair` (unit, real fenced `KVStore`); [design intent — unverified] on a live cluster.
- The blueprint status route uses `BlueprintService.attributedOutcome`, which leaves out a terminal from
  an earlier attempt of a live blueprint.
  Pinned by `BlueprintServiceTest$RedeployAfterPriorFailureTests` (unit, through the real status route); [design intent — unverified] on a live cluster.
- The publish no longer fails when the apply-start retries run out. The terminal left on the record
  belongs to an earlier attempt and no longer reaches this apply, so the old failure message told the
  caller the opposite of what the cluster does.
- **Sibling: a rollback could undo a newer publish of the same id.**
  - An ALL_OR_NOTHING rollback is now one `LeaderTransaction` whose mutations carry the values they
    read, including the rolled-back attempt's blueprint. A publish committed between the build and the
    apply refuses the whole rollback. Before this, its `Remove(AppBlueprintKey)` deleted the newer
    blueprint.
  - After a refusal the leader re-reads. If the blueprint moved, the rollback is superseded. Otherwise
    it rebuilds, bounded by five attempts.
  - Deallocation runs only once the rollback has landed.
  - Without a committed leader record, the rollback is applied unfenced, as before, and logged at WARN.
  - Pinned by `AttemptBoundOutcomeTest.aLateRollbackOfThePreviousAttempt_doesNotRemoveTheNewerAttemptsBlueprint` (unit, real fenced `KVStore`); [design intent — unverified] on a live cluster.
- **Sibling: a same-id rollback deleted the blueprint it restored.**
  - A republish of an id whose earlier apply was still in flight captured that apply, under the SAME
    id, as `previous`.
  - The restore wrote `Put(id, previous)` and `Remove(id)` in one batch, so the blueprint was deleted
    and its slices deallocated.
  - A same-id restore now writes the Put alone, under the rolled-back attempt's id. It removes only the
    slices that the rolled-back attempt added.
  - Pinned by `AttemptBoundOutcomeTest.rollingBackARepublishOfTheSameId_restoresThePreviousBlueprint_ratherThanDeletingIt` (unit, real fenced `KVStore`); [design intent — unverified] on a live cluster.
- Wire: `AppBlueprintValue` and `DeploymentOutcomeValue` gain a trailing `attemptId`. This is pre-GA, so
  no migration is provided. The re-recorded wire baseline differs from `40dfc0519` on those two SHAPE lines only.
