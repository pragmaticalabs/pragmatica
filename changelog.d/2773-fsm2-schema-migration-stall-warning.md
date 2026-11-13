### Fixed (2026-11-12 — #2773: FSM2 reports a stalled schema migration)
- **FSM2: a stalled schema migration is reported (#2773).** A failed migration-lock write abandons the attempt with no retry and no
  status write, which left a PENDING record and no lock and no operator warning. The FSM2 reconcile now has a stall check
  (`Schemas.sweepStalls`): a PENDING or MIGRATING record with no live lock and no write or leader dispatch for longer than the orchestrator's
  longest retry backoff (45 s) plus two reconcile ticks (105 s with the default 30 s tick, `Timing.DEFAULT`) raises `schema-migration-stalled`
  (subject: the datasource), naming the status, the held slices and the lever; `schema-migration-progressed` closes it when the record
  leaves PENDING/MIGRATING, gains a live lock, is dispatched or reset by the leader, or is removed. A dispatch or reset by the leader (also at
  `Rebuild` and on a restore, which keeps the backup's old `updatedAt`) restarts the quiet time, so a record the leader has just acted on is not
  reported. An operator write that dispatches nothing (`POST /schema/migrate` on MIGRATING) does not close an open report. The lever is chosen by
  the retry route's own predicate (`SchemaStatus.canRetryManually`): `aether schema retry` for PENDING; for MIGRATING the retry route refuses, and
  the warning names the current leader and `aether nodes shutdown <leader>` (`nodes drain` does not move leadership): the next leader's
  rebuild resets the record to PENDING and re-runs it. That re-run is under the database migration fence (#2591) only where the datasource has
  one; for a datasource reported `schema-migration-unfenced` it is unfenced, and a migration still running there can run twice. The open
  warning is part of the committed open set (`FsmWarningKey`), so a successor leader inherits it. The check dispatches and writes nothing.
