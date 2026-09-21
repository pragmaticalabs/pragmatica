### Fixed (2026-09-21 — #958: RabiaEngine identical-content self-collision left the first caller's promise unsettled)
- **A batch id is a SHA-256 of the command content, so two callers submitting byte-identical commands
  register the same id.** `RabiaEngine.registerBatch` stored the second submission with a plain
  `put()`, which **replaced** the pending batch and dropped the first caller's `CorrelationId` from the
  local map. `commitChanges` completes only the correlation ids of the local batch, so the first caller's
  promise never settled and surfaced as `ApplyTimeout` although its command had applied — the caller's
  outcome and the committed state disagreed. [mechanism: `RabiaEngine.registerBatch` → `pendingBatches.put`;
  `RabiaEngine.commitChanges` → `pendingBatches.remove(id)` → `correlationMap.remove` per local id]
- `registerBatch` now merges into an already-pending batch with the same id via `StateMachine.merge`,
  exactly as `doHandleNewBatch` already does for a batch arriving from another node. Guarantee: every
  caller's promise completes with its own command's outcome, two callers submitting identical commands
  each complete, and the command is applied once. Retries, idempotent republishes and convergence loops —
  the paths that run during recovery — are the ones that produce identical content.
  [verified: `RabiaEngineTest.identical_concurrent_submissions_each_complete_and_the_command_applies_once`
  — two concurrent `apply()` calls, one V1 decision; RED on the unmodified base with
  `Failure(ApplyTimeout[timeoutMillis=2000])` on one caller while the other succeeded and the command
  had applied once]
- [unverified: the remote-submission path (`handleSubmit` from a peer's `SubmitCommands`) shares the
  same `registerBatch` and is fixed by the same hunk, but is not separately pinned]
