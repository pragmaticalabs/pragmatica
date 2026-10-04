### Fixed (2026-10-04 — #1883: the leader's ISR shrink and the owner's expansion reversed each other under an asymmetric view)
- **One liveness input for ISR membership.**
  - **Cause:** the owner expanded the ISR from its own replica registry, and the leader shrank it from its own
    membership view. With a replica one side listed and the other did not, each commit undid the other's, and every
    commit fired a reconcile on every node (10 ISR commits in 5 rounds in the v1877 probe).
  - **Fix:** the leader records the members it removes for liveness in the same commit as the shrink, as the
    ownership record's new `fenced` set (bounded, newest 16 kept: the bound ends the fight while the core has at most 16 members, and a perpetual fight is possible beyond that), and unfences a member when it lists it live again.
    The owner never expands a fenced member. A reconcile of a settled record commits nothing.
  - **Wire:** `StreamPartitionOwnershipValue` gains `fenced` (pre-GA, no mixed-version support).
- **New cluster events.** `STREAM_ISR_BELOW_MINIMUM` (WARNING) when a partition's committed ISR falls below its
  `confirmation_factor` and every acknowledged publish is refused, and `STREAM_ISR_RESTORED` (INFO) when it reaches the
  factor again. Derived on every node from the committed ownership change and published once by the cluster-events
  owner; never per ISR commit.
- **Event identity (also the phase 1 failover events).** `STREAM_ISR_BELOW_MINIMUM`, `STREAM_ISR_RESTORED`,
  `STREAM_FAILOVER_REFUSED` and `STREAM_FAILOVER_RESOLVED` carry a deterministic `details.eventId` derived from the committed
  ownership record (partition, epoch, term, ISR version), so two nodes that both pass the events-owner gate during a
  membership change publish one event as far as every reader that de-duplicates by `eventId` is concerned. Limit: a failover
  refusal repeated in the identical state after its owner returned repeats its id.
- **A confirmation-factor change announces too.** Raising `confirmation_factor` above the committed ISR size stalls every ack
  with no ISR commit; it now raises `STREAM_ISR_BELOW_MINIMUM` once (and `STREAM_ISR_RESTORED` when the factor moves back),
  derived from the factor the node enforces before and after the committed config. A lowering a running stream does not adopt
  announces nothing.
