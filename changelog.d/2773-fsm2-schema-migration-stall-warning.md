### Fixed (2026-11-12 — #2773: FSM2 reports a stalled schema migration)
- **FSM2: a stalled schema migration is reported (#2773).** A failed migration-lock write abandons the attempt with no retry and no
  status write, which left a PENDING record and no lock and no operator warning. The FSM2 reconcile now has a stall check
  (`Schemas.sweepStalls`): a PENDING or MIGRATING record with no live lock and no write for longer than the orchestrator's longest retry
  backoff (45 s) plus two ticks raises `schema-migration-stalled` (subject: the datasource), naming the status, the held slices and the
  lever; `schema-migration-progressed` closes it when the record moves, gains a live lock or is removed. The lever is chosen by the
  retry route's own predicate (`SchemaStatus.canRetryManually`): `aether schema retry` for PENDING; for MIGRATING the warning says the retry
  route refuses it and the next leader's rebuild resets the record to PENDING (re-run under the database migration fence, #2591). The open
  warning is part of the committed open set (`FsmWarningKey`), so a successor leader inherits it. Nothing is dispatched or written by the check.
