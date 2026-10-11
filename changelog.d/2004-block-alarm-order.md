### Fixed (2026-10-07 — #2004: a block or catch-up alarm's recovery could be delivered before its raise)
- **A clear that landed between a warning being recorded and its raise published the recovery first.** The aggregator drops a recovery with no
  open warning, so the CRITICAL stayed open until the next episode. Reproduced on the base for the owner gate's lineage slot (the Nth refusal's raise
  against the quorum-loss clear, 54 of 54 runs) and for the catch-up transport's not-answering episode (`stream-catchup-source-not-answering` against
  its restoration, 130 of 400 adversarial episodes).
- Recording a warning and raising it, and removing it and resolving it, now run under one leaf monitor per site, so the delivered order is
  `[raise, resolve]` or nothing, never `[resolve, raise]`. While a monitor is held only a map and the warning's log line plus bounded hand-off run.
  The sites: the owner activation gate (`OwnerActivation`), the promoted owner's backfill gate (`PartitionBackfill`, oversized-peer block, which also
  reads its flight's `current` inside the monitor so a superseded flight cannot raise after its successor's clear), `ForwardCatchupTransport`
  (episode report and restoration) and `StreamPartitionManager` (a refused divergent-tail cut and its close/resume).
- In the owner gate the lineage raise against quorum loss is the pair that crosses threads in production; `report`, the unreachable wait and the other
  resolves run on the activation chain and are ordered defensively only (the unreachable-wait and divergent-peer CRITICALs were not reproduced stuck
  open on the base).
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/BlockAlarmOrderingTest.java, aether/aether-stream/src/test/java/org/pragmatica/aether/stream/replication/CatchupAndBackfillAlarmOrderTest.java, aether/aether-stream/src/test/java/org/pragmatica/aether/stream/RefusedCutAlarmOrderTest.java, aether/aether-stream/src/test/java/org/pragmatica/aether/stream/replication/PartitionBackfillAlarmOrderingTest.java]`
