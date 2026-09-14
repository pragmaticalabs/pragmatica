### Fixed (2026-09-14 — #766: the schema-migration lock claim was check-then-act across nodes)
- **`SchemaOrchestratorService.acquireLock` read the KV lock (`isLockHeld`) and wrote it (`Put`) in two
  steps, and its single-flight fence (`inFlightMigrations`) is per orchestrator instance.** Two nodes
  that both observed the lock free — absent, or present-and-expired after a holder died — before
  either write committed both proceeded into `migrate()` and duplicate-keyed `aether_schema_history`
  (23505), which marked the datasource FAILED and held every slice of the blueprint in LOADED.
  [mechanism: `migrateIfNeeded` → `executeMigrationFlow` → `acquireLock`; the KV read and the
  consensus `Put` are separate operations with no compare]
- `SchemaMigrationLockValue` now carries a `lockVersion` and implements `VersionFenced` (RFC-0018,
  #570; the #805 `outcomeVersion` precedent): the applier accepts a claim only as the immediate
  successor of the committed value (a first write against an absent key passes), so the second of
  two racing claims is rejected — on the absent path AND on the expired-lock takeover path, where
  both takers derive the same successor version. Because a rejected write is silent, `acquireLock`
  now confirms after its apply resolves by re-reading the committed value and comparing it to the
  one it wrote; a mismatch (or an absent key, if the winner already released) fails the attempt with
  `LockAcquisitionFailed` and releases the in-flight fence.
  [verified: `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/schema/SchemaOrchestratorLockClaimRaceTest.java`
  — two orchestrators (two nodes) on one real `KVStore`; both read the lock free, the first claim
  commits and its holder migrates without completing, the second claim commits against the held lock;
  exactly one `migrate()`, the loser fails with `LockAcquisitionFailed`. Absent-lock and expired-lock
  variants. Removing `VersionFenced` alone, or the re-read confirm alone, reddens both]
- **Wire format.** A committed `AetherValue` gained a record component: the generated codec and the
  `KVStoreSerializer` text form (`schema-lock`, 4 → 5 fields) both change. rc4 promises no cross-rc
  wire compatibility; that contract is #434/#666's, and mixed-version co-application is unsupported
  on the rc line (see `VersionFenced`). The wire tag (`SystemTags` 1636) is unchanged, so
  `SystemCodecPinningTest`/`WireAssignmentTripwireTest` do not see a component addition — stated
  here rather than implied by their green.
  [verified: `KVStoreSerializerTest.roundTrip_schemaMigrationLock_preservesLockVersion` — the
  `schema-lock` section is ephemeral and never reaches a snapshot, so the serialize/parse pair is
  exercised directly]
- Not changed: a claim that lands after the winner has released runs the migration sequentially
  (the lock serialises, it does not deduplicate); the manager's history-table check is what makes
  that re-run idempotent. [design intent — unverified]
