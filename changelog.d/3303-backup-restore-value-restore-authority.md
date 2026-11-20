### Changed (2026-11-20 — #3303: BackupRestoreValue is the restore authority)
- **The restore decision at `BackupRestoreKey` is now judged by a fixed transition table.** `BackupRestoreValue` is a
  leader-authorized `RestoreAuthority`: a leader transaction replaces it only along restore-authority r3.1 §2 rows 1-10,
  and a bare `Put`, a non-authority write, a delete or a copy at any other key is refused
  `[verified: aether/node/src/test/java/org/pragmatica/aether/node/backup/RestoreAuthorityRowsTest.java]` (single-node
  store, not a multi-node path).
- A restore marker now carries `rejection`, `abandon` and a removed-key count; the new terminal outcome `ABANDONED` opens the
  restore gate. Restore-unit writes are admitted only under an `IN_PROGRESS` marker that is not being abandoned
  `[mechanism: BackupRestoreValue.admitsUnits]`.
- Wire break: `BackupRestoreOutcome` gains `ABANDONED` (ordinal 5; `UNKNOWN` moves to 6) and `BackupRestoreValue` gains three
  components, so `WIRE_GENERATION` is bumped by one; a node at the previous generation is refused at join
  `[verified: integrations/consensus/src/test/java/org/pragmatica/consensus/net/JoinGenerationGuardTest.java]`. Upgrade with
  the stop-the-world procedure.
