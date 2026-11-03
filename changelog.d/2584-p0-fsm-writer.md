### Changed (2026-11-02 — #2584 P0: every leader-issued deployment-FSM write goes through one writer)
- **The leader's deployment FSM writes through `FsmWriter`/`TermWriter` only.** `ClusterDeploymentContext` no longer
  exposes the consensus node; every write site names itself with a `WriteSite` constant. Reflection and source-scan
  tests fail the build if anything else in `cluster.fsm` can reach the applier.
- **A deposed or ended-term leader issues no deployment write.** Every write now checks, before building its commands,
  that the committed leader record names this node and that its term is live (the #2592 rollback check, generalised).
  A refused write fails with `NotLeader` into the site's existing failure path; hierarchy writes keep their `INACTIVE`
  refusal; a refused rollback is abandoned without scheduling a failure reconcile. A rollback with no committed leader
  record to fence it is therefore no longer applied unfenced: it is abandoned and logged at WARN.
- **The stalled-migration reset no longer overwrites a migration that finished meanwhile.** The reset to PENDING is a
  transaction guarded on the MIGRATING record it read, so the applier refuses it if the record moved.
