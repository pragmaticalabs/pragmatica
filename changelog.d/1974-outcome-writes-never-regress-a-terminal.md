### Fixed (2026-10-11 — #1974: an outcome write could regress a terminal, and a SUCCEEDED refused by the version fence was lost)
- **`BlueprintService.confirmOutcomeStart` retried over its own apply's terminal.** "Landed" meant "IN_PROGRESS",
  so a publish whose apply had already settled (the FSM wrote SUCCEEDED for that attempt before the
  confirmation read) was treated as fenced out, and the retry wrote IN_PROGRESS over the real outcome.
  The retry now stops when the record is IN_PROGRESS, describes this publish's own attempt, or when the committed
  blueprint belongs to another attempt (a newer publish owns the id). Only a terminal of an earlier attempt, beside
  this attempt's own blueprint, is retried over.
  Pinned by `BlueprintServiceTest$RedeployAfterPriorFailureTests` (unit, in-memory store without the version fence); [design intent — unverified] on a live cluster.
- **A SUCCEEDED outcome refused by the version fence was lost silently.** `recordSucceededOutcome` derived the
  version when the command was built and never checked it landed; a racing write of the record (a publish's
  IN_PROGRESS) made the applier drop it, and nothing revisits an apply that already left in-flight tracking.
  It now re-reads after the apply resolves and rebuilds against the current version, bounded by five attempts and
  logged at ERROR on exhaustion. It does not retry once a terminal for the attempt is committed or the id carries
  another attempt.
  Pinned by `AttemptBoundOutcomeTest.succeededOutcomeRefusedByARacingWrite_isRebuiltAgainstTheCurrentVersionAndLands`
  and `succeededOutcomeRefusedByANewerPublish_isNotRetriedOverTheNewerAttempt` (unit, real fenced `KVStore`); [design intent — unverified] on a live cluster.
- The FAILED and ROLLED_BACK terminals of an ALL_OR_NOTHING rollback were already rebuilt on a refusal by #972's
  transactional rollback; no change there. [mechanism: `submitRollback` re-invokes the command supplier, which re-derives the version]
- **The first SUCCEEDED write also respected nothing.** It was unconditional, so it could replace a newer attempt's
  FAILED when the store was ahead of the FSM, replace the same attempt's BEST_EFFORT FAILED, or write an outcome for a
  deleted blueprint. It now applies the same check as its retries. Rule per transition, for one blueprint id:
  - `IN_PROGRESS(N)` to `SUCCEEDED/FAILED/ROLLED_BACK(N)`: written.
  - A terminal for `N` to any other write for `N`: refused, with two exceptions. A BEST_EFFORT permanent failure merges
    its slices into a FAILED for `N`, and it also replaces a SUCCEEDED for `N` (a partial permanent failure reads FAILED,
    in either order). A BEST_EFFORT FAILED is the FSM's own decision that a slice is permanently failed (the operator
    was told, the artifact is no longer redeployed), not a guess written on a timeout, so a later SUCCEEDED does not erase it.
  - A write for `N` while the committed blueprint is another attempt: refused for the SUCCEEDED and the rollback writers;
    the BEST_EFFORT merge writes under the committed attempt. While the blueprint is absent: SUCCEEDED refused, BEST_EFFORT
    writes nothing, but an ALL_OR_NOTHING rollback still writes FAILED for `N` (its fence expects the blueprint absent),
    so a delete during an in-flight apply followed by a permanent slice failure leaves a FAILED for a deleted id. Known limit.
  - A terminal for an earlier attempt `M` to `IN_PROGRESS(N)` or to `SUCCEEDED(N)`: written.
  Pinned by `AttemptBoundOutcomeTest.storeAheadOfFsm_newerAttemptsTerminal_isNotRegressedByStaleSucceeded`,
  `bestEffortFailedOwnAttempt_thenInFlightCompletes_keepsTheFailed` and `deleteWhileApplyInFlight_thenCompletes_writesNoOutcome`
  (unit, real fenced `KVStore`); [design intent — unverified] on a live cluster.
