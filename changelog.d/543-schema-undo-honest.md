### Fixed (2026-09-04 — #543: schema undo was unreachable — both `undo` and `baseline` fabricated their result instead of running it)
- **`POST /api/v1/schema/undo/{datasource}` wrote a bare `PENDING` status at the target version and
  reported success without ever invoking the schema orchestrator.** `PENDING` is a **blocking**
  status — every "successful" undo call silently withheld the owning blueprint's slices from
  activation instead of undoing anything, and nothing about the response distinguished this from a
  real undo. `POST /api/v1/schema/baseline/{datasource}` had the mirror defect: it wrote a
  fabricated `COMPLETED` status directly, with zero database interaction, so a baseline that should
  have been refused (applied history past the requested version) instead reported false success.
- **Both routes now delegate to `SchemaOrchestratorService`'s real `undoTo` / `baseline` operation**
  — `undo` runs the target version's `U<version>__*.sql` script against the datasource; `baseline`
  verifies and records `V001..V{N}` as applied without executing them — and report the outcome from
  a re-read of the KV record the orchestrator itself wrote, never an echo of the request. Status on
  success is always `COMPLETED`, never a bare `PENDING`.
  [mechanism: `SchemaRoutes.undoToVersion`/`baselineAtVersion` delegate to
  `nodeSupplier.get().schemaOrchestrator()` and re-read via `reportOutcome` before responding]
- **BLOCKING 1: every existing undo test substituted a stub orchestrator, so nothing failed when
  this delegation was reverted to a stub.** `SchemaRouteStatusTest`'s `orchestratorFailingUndo`/
  `RecordingOrchestrator` and `ClusterDeploymentStateActiveTest`'s `RecordingSchemaOrchestrator`
  never exercised `SchemaOrchestratorServiceInstance` — nothing in the repo instantiated it. New
  tests run the real manager end to end: undo executes its `U<version>__*.sql` scripts in reverse
  order against a recording connector and removes the matching `schema_history` rows in reverse
  order.
  [verified: aether/node/src/test/java/org/pragmatica/aether/api/routes/SchemaRoutesUndoRealManagerTest.java#undoToVersion_runsUndoScriptsInReverseOrder_againstTheRealManager,
  #undoToVersion_removesHistoryRowsInReverseOrder_forEveryUndoneVersion]
- **BLOCKING 2: the reported version is now pinned against a manager double that disagrees with the
  request, closing a non-discriminating test.** The prior baseline test's stub echoed the request
  (`schemaResult(scripts.size(), baselineVersion, 1L)`), so it passed whether production wrote
  `result.currentVersion()` or the raw parameter, and `stubConnector()` returned empty — no database
  was ever compared. A new manager double reports a version that deliberately differs from what was
  requested; the response and the KV record are both asserted to reflect the manager's version, for
  `undo` and `baseline` alike.
  [verified: aether/node/src/test/java/org/pragmatica/aether/api/routes/SchemaRoutesVersionIntegrityTest.java#undoToVersion_recordsTheManagersReportedVersion_notTheRequestedTarget,
  #baselineDatasource_recordsTheManagersReportedVersion_notTheRequestedVersion]
- **BLOCKING 3: `undo`/`baseline` now bound the manager call with the same timeout `migrate` already
  uses, instead of waiting on it forever.** `provisionAndRun` had no bound; `migrate`'s own bound
  carries a comment warning that an unbounded runaway script holds the in-process fence and the KV
  lock indefinitely and locks every caller out — `undo` and `baseline` share that fence and lock and
  had no bound of their own. A promise that never settles now times out at
  `SchemaPolicy#migrationTimeout`, releases the fence and the KV lock, and writes a terminal
  `FAILED` record instead of leaving both held forever; the route answers `504 Gateway Timeout`.
  [verified: aether/node/src/test/java/org/pragmatica/aether/api/routes/SchemaRoutesUndoBaselineTimeoutTest.java#undoToVersion_boundsTheManagerCall_releasesTheFence_andMarksFailed_onTimeout,
  #baselineDatasource_boundsTheManagerCall_releasesTheFence_andMarksFailed_onTimeout]
- **Leader-binding closes a concurrent-mutation gap the fix would otherwise reopen.**
  `SchemaOrchestratorServiceInstance`'s single-flight fence is an in-process `ConcurrentHashMap` —
  it serializes calls on one node and does nothing for a second node writing the same schema row at
  the same time. A non-leader node now refuses `undo`/`baseline` outright with `409 Conflict`
  (`SchemaNotLeader`, naming the current leader when known) rather than attempting the write and
  rolling back.
  [verified: aether/node/src/test/java/org/pragmatica/aether/api/routes/SchemaRouteStatusTest.java#LeaderBinding]
- **Orchestrator failures now surface as typed 409/422 responses instead of falling back to a
  bare `500`** — neither route had ever reached the orchestrator before this fix, so no orchestrator
  failure had ever surfaced through them. **SHOULD-FIX 4: all eleven `SchemaError` variants now
  implement `HttpStatusAware`**, not only the three this fix's own routes raise directly:
  `UndoNotAvailable` (422) and `ChecksumMismatch` (422) apply to `undo`; `BaselineConflict` (409)
  applies to `baseline`; `MigrationArtifactUnresolved` (422) and `MigrationSetUnavailable` (422) —
  raised by this PR's own `resolveMigrationScripts` — and `InvalidMigrationFilename` (422) apply to
  both. `LOCK_HELD`, previously a bare `Cause` that answered `500` for a concurrent attempt, is now
  typed 409. Two variants, `MigrationFailed` (422) and `DatasourceUnreachable` (503), are typed but
  have no construction site anywhere in the current code, so they cannot yet surface through any
  route; a genuinely unmapped failure, or either of these two should one day be wired up, still
  answers `500` [design intent — unverified]. Each error names the operator's recovery action
  (publish the missing script; restore the script or re-baseline past the drift; undo to the target
  version instead of baselining over applied history).
  [verified: aether/node/src/test/java/org/pragmatica/aether/api/routes/SchemaRouteStatusTest.java#ErrorMapping]
- **SHOULD-FIX 5: the leader check is itself check-then-act, now disclosed.**
  `SchemaRoutes.requireLeader` reads `node.isLeader()` once and lets the manager call proceed with
  no re-check; leadership can change between that read and the call's completion. Same
  missing-compare-and-set shape as #766's lock race, one layer up at the route's leader gate —
  disclosed under the same #766 callout in `management-api.md` rather than as a separate known
  limitation (per review-round ruling), and called out in a doc comment on `requireLeader` itself.
  Not fixed here; no separate ticket, since #766's atomic compare-and-set is the natural fix for
  both.
  [mechanism: SchemaRoutes.requireLeader's doc comment; management-api.md's `acquireLock` callout]
- **An undo now writes `COMPLETED`, never `PENDING`, so the cluster-wide reconciler never
  re-dispatches it as a stalled migration.** `ClusterDeploymentState.collectSchemaRecovery` (both the
  KV-rebuild and live-Put dispatch paths) gates strictly on `SchemaStatus.PENDING`; writing
  `COMPLETED` structurally excludes the record from both paths without any change to the reconciler
  itself.
  [verified: aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/cluster/fsm/ClusterDeploymentStateActiveTest.java#SchemaRecoveryDispatch]
  [mechanism: `ClusterDeploymentState.collectSchemaRecovery`'s PENDING-only guard, unchanged by this fix]
- Docs (`management-api.md`, `cli.md`) updated to describe the real behavior — leader-binding,
  actual `COMPLETED` status, the real outcome message format, and the new 409/422 error rows —
  replacing descriptions of the old fabricated-status behavior. The CLI's `undo`/`baseline`
  subcommands needed no code change: `OutputFormatter.checkResponseError` already renders any
  `HttpStatusAware` error generically, the same mechanism that already covered `baseline`'s
  pre-existing 404 case (#551). The dashboard's schema panel needed no change either — it renders
  whatever status is actually in KV, which this fix makes accurate, and never exposed undo/baseline
  as dashboard actions (only `retry`); adding such actions is unrelated feature work, not part of
  this fix.
- Every new/updated assertion is mutation-probed: leader-check removal, the reconciler's PENDING
  gate widened to include COMPLETED, and an unwrapped-cause-swap on the undo path each drive the
  test(s) they claim to pin red, and green again on revert.
- **NOTE 7, not fixed here: an undo (or baseline) is not sticky across a republish.** The
  deployment contract is declarative — the artifact's declared version always wins on a publish —
  so `BlueprintService.buildMigrationCommand` writes `PENDING` at the artifact's declared max
  version unconditionally, with no comparison against the live record; nothing documented, before
  this fix, that a redeploy erases an undo. Republishing (or redeploying) a blueprint that declares
  a version higher than an undo's target re-arms migration and re-applies from scratch whatever
  rows the undo removed. `buildMigrationCommand` is intentionally unchanged — this
  detect-and-migrate-forward behavior is correct for the normal case, and an undo/baseline is an
  operator intervention performed between deploys, not persistent state. Documented in
  `management-api.md`'s undo and baseline sections and in `cli.md`: the operator's correct sequence
  is to undo, then deploy a blueprint that declares the lower version, or accept the re-migration.
  A guard that refuses (or requires `force` for) such a publish is tracked separately in #834.
  [mechanism: `AetherSchemaManager.executeUndoStep` (:684-692) runs the down-script and deletes
  that version's row from `aether_schema_history`, descending through every version above the
  target; every publish writes `(artifact's declared maxVersion, PENDING)` to the datasource's
  single `SchemaVersionKey` via `BlueprintService.buildMigrationCommand` (:520-543) without reading
  the record first; `migrate()` then reads the real history, sees the undone rows gone, and
  re-applies them. Only a republish triggers this — the recovery scan and a leader change never
  touch a `COMPLETED` record.]
- **Known limitation, not fixed here: `acquireLock`'s cross-node lock check is not atomic (#766).**
  `undo`, `baseline`, and `migrate` all share `SchemaOrchestratorService.acquireLock`. Its
  cross-node `SchemaMigrationLockKey` check is a read (`isLockHeld`) followed by a separate write
  (`Put<SchemaMigrationLockValue>`), not an atomic compare-and-set, so two concurrent dispatches can
  both observe the lock free before either writes it. #766 reproduced this live on a 5-node Forge
  run — two dispatches within two seconds, the second reaching `aether_schema_history` and failing
  on a duplicate-key constraint, which marked the datasource `FAILED`. This is a known defect, not
  a design choice; the fix needs an atomic compare-and-set on the lock key and is tracked in #766,
  outside this PR's scope.
