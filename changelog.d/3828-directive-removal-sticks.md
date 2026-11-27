### Fixed (2026-11-27 — #3828: a removed activation directive was written back by a fresh join)
- **A directive that was committed and then removed is no longer derived again for the same process incarnation.** A worker join input
  (the B11 placement re-drive raises one on every `NodePlacement` put, so does the membership projector) or a reconcile retry assigned the
  worker a policy community right after an operator's removal. The leader model now remembers, per node, that its committed directive was
  removed; `Joins.assign` (the one caller of `Directory.assignWorker`/`assignCore`) writes nothing while the mark stands, and a refused
  draft of the removed directive is dropped instead of resubmitted. A lost or refused directive that was never committed is still derived
  again (the B11 heal). The mark ends on positive evidence of another process (both tokens known and unequal); a token learned after the
  removal is adopted, not read as a new process.
- **Not the model's own retirement.** C17b's removal of a departed worker's directive (with its record, one transaction) leaves no mark: a process
  that joins under the retired id is a new admission, directed by J5 (#3028).
- **Limits.** The mark is leader-local: a new leader's `Rebuild` starts without it, and a live worker without a directive is then
  indistinguishable from one never directed. The write carries no KV witness of the removal (a removal leaves no trace to witness).
