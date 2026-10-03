### Fixed (2026-10-03 — #806: the schema migration lock expired under a slow holder, and its bare release deleted the successor's lock)

- **The class: a lease shorter than the operation it guards, released by an unfenced delete.** The lock was a
  fixed 5-minute claim (`LOCK_TTL_MS`) around a migration bounded at 15 minutes (`SchemaPolicy.DEFAULT_MIGRATION_TIMEOUT`),
  and was released by a bare `Remove`. A second node could take the expired lock; the first holder's
  later release then deleted the *second* node's lock, and a third attempt migrated beside it.
- **The holder now renews its lease while its attempt is in flight.** Every `LOCK_TTL_MS / 3` the holder
  writes the next `lockVersion` with a later `expiresAt` (an owner-fenced Put: it is sent only while the
  committed value is still one this attempt wrote). The lock lasts exactly as long as the operation, with
  no timing relation between two constants, and a holder that dies stops renewing and loses the lock
  within `LOCK_TTL_MS`. A holder that finds its lock taken (it stalled past the lease) stops renewing and
  logs at ERROR; it cannot recall a running script, so the database's own history uniqueness remains the
  backstop for that case.
- **The release is a compare-and-delete.** New marker `WitnessedRemoval` (extends `VersionFenced`): a
  `Remove` of a committed `WitnessedRemoval` value is applied only when its witness EQUALS the committed
  value. `SchemaMigrationLockValue` implements it and the holder releases with the value it last wrote
  (after waiting for any in-flight renewal, so a late renewal cannot resurrect the lock). Equality, not the
  version number: a released key restarts its chain, so a stale holder's `v1` would otherwise match a later
  unrelated `v1` claim.
- **#972 (unwitnessed `Remove` of a `VersionFenced` record) is covered by the mechanism, not by this
  change.** The marker is opt-in because other `VersionFenced` records (`DeploymentOutcomeValue`) still
  have witnessless removers (`BlueprintService.removeFromStore`); adopting it there is `implements
  WitnessedRemoval` plus a witness at that call site.
- **Wire format:** no component changes; the marker interface is not encoded. rc4 promises no cross-rc
  applier compatibility (see `VersionFenced`).
