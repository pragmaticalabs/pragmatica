### Fixed (2026-09-29 — #217: a schema baseline could silently cancel an in-flight migration)
- **`POST /api/v1/schema/baseline/{datasource}` accepted any prior state.** Baseline writes `COMPLETED`,
  and the orchestrator dispatches only `PENDING` records, so a baseline over a `PENDING` or `MIGRATING`
  datasource meant that migration never ran (the RC1 parallel-suite race).
- It is now refused with `409 Conflict` (`BaselineOverInFlightMigration`) while the status is
  `PENDING`, `MIGRATING` or `UNKNOWN`. `?force=true` (CLI: `aether schema baseline <ds> -v N --force`)
  overrides it deliberately and is written to the audit log as `SCHEMA_BASELINE_FORCED`. Refusing a
  rewind was already done by the schema manager's `BaselineConflict` (#543), from the database's
  applied history; `force` bypasses neither that nor the leader binding.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/api/routes/SchemaRoutesBaselineTest.java`
  `InFlightGuard` — route plus the real orchestrator over a fake schema manager, not a cluster run]
- The status is re-checked under the orchestrator's migration fence, before the database is touched and
  just before `COMPLETED` is written, so a deploy that arms a migration after the route's check is not
  overwritten (found by v1617). The write itself is not a compare-and-set: a PENDING landing between that
  last read and the Put is still possible. [mechanism: re-read under the fence; `Put` is unconditional]
