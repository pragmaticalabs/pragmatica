### Changed (2026-09-29 — #1533: a whole-cluster restart restores the change-triggered KV backup)
- **Restart = a regular start of fresh cores, then the restore.** The leader of a cold-started cluster
  decides once whether to restore the `[backup]` head (`RESTORED`, `FRESH`, `SKIPPED_EXISTING_STATE`,
  `DISABLED`), committed as the runtime marker `BackupRestoreKey` (wire tags 1718–1720). A restore applies
  the head in leader transactions of at most 8 MiB and moves the incarnation past every incarnation the
  backup history records for its lineage (#1621). An interrupted restore is resumed from its own commit.
  `[verified: ApiKeyFullRestartForgeTest — fresh cores; the key is accepted, the incarnation rises, the
  slice and stream owners are rebuilt on the fresh nodes; restore = "fresh" refuses the key]`
- **The restore gate is a mechanism.** Until the decision commits, every backup-enabled node refuses writes
  to cluster state on its consensus submit path (`RestorePending`, retryable); forwarded worker writes are
  caught by the core that receives them. `[verified: EmberKvBackupRestoreTest]`
- **`[backup] restore = "auto" | "fresh"`** (new). `[backup] interval` is removed.
- **Unreadable or unreachable backup → blocked, loudly:** one `BACKUP_RESTORE_BLOCKED` warning naming the
  exits, retry capped at 60 s; never a silent fresh boot.
- **Restore normalisation** (one place): community ACTIVE/DEGRADED → FORMING; an IN_PROGRESS deployment
  outcome and entity checkpoint pointers are not restored (`BACKUP_RESTORE_ENTITY_CHECKPOINTS_DROPPED`
  names the partitions); schema MIGRATING → PENDING.
- **One restore-commit hook** re-drives the components that load cluster state once at activation: the
  bootstrap config seed, the rollout manager and the A/B test manager.
- **Forks are refused, not overwritten.** The backup header carries the committed `incarnationId` (#1529 part
  2's per-incarnation ULID, minted at genesis, restore and declare-genesis; leader changes keep it). Two
  clusters restored from the same backup at once reach the same lineage and incarnation under different
  incarnation ids: the backup never lets one replace the other's head (`BACKUP_FORKED`, both ids named);
  detection is on the second writer. `declare-genesis` resolves it as a takeover (the other cluster's
  `BACKUP_HEAD_AHEAD` then says the head is at a higher incarnation it will never pass). Backup format:
  header line `incarnation-id=` (format version stays 1).
  `[verified: KvBackupServiceTest.Fork]`
- **Backup conditions are cluster events (#1617):** `BACKUP_RESTORE_BLOCKED`, `BACKUP_RESTORE_ENTITY_CHECKPOINTS_DROPPED`
  and `BACKUP_FORKED` are raised as `OperatorWarning` events (codes `backup-restore-blocked`,
  `backup-restore-entity-checkpoints-dropped`, `backup-forked`, subsystem `kv-backup`) as well as logged.
  `[verified: BackupWarningOperatorEventTest; EmberKvBackupRestoreTest — the blocked restore reaches the event log]`
- **`declare-genesis` clears the same floor as a restore:** it commits `max(own, head, highest recorded
  for its lineage) + 1`, so it never reuses an incarnation the backup history records for its lineage
  `[verified: KvBackupServiceTest.Genesis#declareGenesis_clearsEveryIncarnationTheHistoryRecordsForThisLineage]`.
- **Entity folds validate a checkpoint pointer at its point of use:** an unreadable, undecodable or
  beyond-the-log-head pointer is ignored and the partition folds from its log.
- **Removed:** the old consensus-snapshot persistence (`RabiaPersistence.gitBacked`,
  `GitBackedPersistence`, `VoterAuthoritySnapshotCodec`, `AetherNode.snapshotToBase64` /
  `base64ToSnapshot`). Consensus runs in memory on every node.
- **Limits:** entity state and stream records do not survive a whole-cluster restart; with no `[backup]
  remote` the restore reads only the deciding leader's local repository (`BACKUP_RESTORE_SOURCE_LOCAL`).
- **Fixed on the way:** `BootstrapModule` submitted the runtime DHT core-partition write and the
  cluster-config seed in one batch, so the restore gate would have held the DHT bootstrap back; they are now
  separate.
