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
