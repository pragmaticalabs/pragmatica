### Fixed (2026-10-03 — #806: the schema migration lock expired under a slow holder, and its bare release deleted the successor's lock)

- **The class: a lease shorter than the operation it guards, released by an unfenced delete.** The lock was a
  fixed 5-minute claim (`LOCK_TTL_MS`) around a migration bounded at 15 minutes (`SchemaPolicy.DEFAULT_MIGRATION_TIMEOUT`),
  and was released by a bare `Remove`. A second node could take the expired lock; the first holder's
  later release then deleted the *second* node's lock, and a third attempt migrated beside it.
- **The holder now renews its lease while its attempt's promise is in flight.** Every `LOCK_TTL_MS / 3` the
  holder writes the next `lockVersion` with a later `expiresAt`, only while the committed value is one of its
  own. The lock lasts as long as the PROMISE, not as long as the work: `Promise.timeout` detaches the promise
  from a JDBC script that keeps running, after which the lease is released although the script may still
  execute. A holder that dies stops renewing and loses the lock within `LOCK_TTL_MS`.
- **Ownership is by claim, release is a tombstone.** A committed value belongs to an attempt when its `heldBy`
  and `acquiredAt` equal the claim's and its `lockVersion` is at least the claim's. The release is a fenced Put
  of the next version with `expiresAt = 0` — never a `Remove` — so the version chain never restarts and a
  renewal that timed out but is decided later is fenced out by version instead of overwriting the next
  holder's claim. The release first waits for any renewal in flight. A renewal that lands late and is still
  this attempt's own is recognised, not mistaken for a loss.
- **A bare `Remove` of the lock is refused.** New marker `WitnessedRemoval` (extends `VersionFenced`): the
  applier deletes such a value only for a witness EQUAL to it. `SchemaMigrationLockValue` implements it; no
  production code removes the lock any more (the stalled-migration reset in `ClusterDeploymentState` no longer
  does either: it ran only for an expired lock, which the next acquire takes over).
- **[limit:] exclusivity is mechanism-backed at the KV level, timing-dependent at the database.** With
  healthy consensus, no two nodes hold the lock at once (fenced version chain). Whether two nodes EXECUTE a
  migration at once, when the holder stalls or is partitioned, depends on its renewals committing within 2/3 of
  the TTL and on clock skew staying below that margin (`expiresAt` is the holder's clock, `isExpired` is the
  taker's). A holder that loses its lease keeps running its script and stops renewing; it cannot be recalled.
- **[limit:] the database history is a partial backstop only.** The `(version, type)` key collides only on the
  versioned transactional path and only on dialects with transactional DDL; MySQL, MariaDB and Oracle commit DDL
  implicitly, and the autocommit path and undo have no such backstop. Fencing on the database side (the
  `lockVersion` written inside the history transaction, or an advisory lock) is the structural follow-up.
- **#972** (unwitnessed `Remove` of a `VersionFenced` record) is covered by the mechanism, not by this change:
  the marker is opt-in because `DeploymentOutcomeValue` still has a witnessless remover
  (`BlueprintService.removeFromStore`).
- **Wire format:** no component changes; the marker interface is not encoded. rc4 promises no cross-rc applier
  compatibility, and mixed old/new appliers would decide a `Remove` differently (see `VersionFenced`).
