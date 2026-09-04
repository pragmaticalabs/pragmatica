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
- **Leader-binding closes a concurrent-mutation gap the fix would otherwise reopen.**
  `SchemaOrchestratorServiceInstance`'s single-flight fence is an in-process `ConcurrentHashMap` —
  it serializes calls on one node and does nothing for a second node writing the same schema row at
  the same time. A non-leader node now refuses `undo`/`baseline` outright with `409 Conflict`
  (`SchemaNotLeader`, naming the current leader when known) rather than attempting the write and
  rolling back.
  [verified: aether/node/src/test/java/org/pragmatica/aether/api/routes/SchemaRouteStatusTest.java#LeaderBinding]
- **Orchestrator failures now surface as typed 409/422 responses instead of falling back to a bare
  `500`** — neither route had ever reached the orchestrator before this fix, so no orchestrator
  failure had ever surfaced through them. `UndoNotAvailable` (422 — the artifact carries no matching
  undo script for the requested version) and `ChecksumMismatch` (422 — the script's content no
  longer matches its recorded checksum) apply to `undo`; `BaselineConflict` (409 — versioned
  migrations already applied past the requested baseline version) applies to `baseline`. Each error
  names the operator's recovery action (publish the missing script; restore the script or
  re-baseline past the drift; undo to the target version instead of baselining over applied history).
  [verified: aether/node/src/test/java/org/pragmatica/aether/api/routes/SchemaRouteStatusTest.java#ErrorMapping]
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
- **Known limitation, not fixed here: `acquireLock`'s cross-node lock check is not atomic (#766).**
  `undo`, `baseline`, and `migrate` all share `SchemaOrchestratorService.acquireLock`. Its
  cross-node `SchemaMigrationLockKey` check is a read (`isLockHeld`) followed by a separate write
  (`Put<SchemaMigrationLockValue>`), not an atomic compare-and-set, so two concurrent dispatches can
  both observe the lock free before either writes it. #766 reproduced this live on a 5-node Forge
  run — two dispatches within two seconds, the second reaching `aether_schema_history` and failing
  on a duplicate-key constraint, which marked the datasource `FAILED`. This is a known defect, not
  a design choice; the fix needs an atomic compare-and-set on the lock key and is tracked in #766,
  outside this PR's scope.
