### Fixed (2026-11-13 — #3085: FSM2 published a family warning before the WARNINGS row reflecting it committed)
- **A warning raised in a drain whose leader was deposed before its WARNINGS write committed stayed open for ever**: the opener
  reached the operator-warning sink in the step that raised it, the fenced write never landed, and the successor inherited an
  open set without it, so nothing ever closed it. The FSM now holds the family warnings and recoveries a step raises
  (`Warnings.noteRaised`, `ClusterModel.warningsHeld`) and publishes them only once the WARNINGS transaction that carries them is
  accepted, or when nothing is in flight and the committed mirror already equals the family sets.
- **Guarantee**: a family warning is published only once the WARNINGS row reflecting it commits [mechanism: held in the model, released from `Warnings.accepted`
  / `noteRaised`; a deposition or `Rebuild` discards the model]. A warning `Rebuild` raises after `inherit` (a schema hold re-raised at
  activation) is held the same way. One raised in a drain whose leader is deposed before the commit is dropped (never published), not stuck
  open; an event-only warning raised in that window is raised again only if its event recurs. Third outcome: when the write commits but the
  leader processes `Deactivate` (or a reincarnation drops the settlement as stale) before the settlement, the row is committed and open, the
  warning was never published, and the successor inherits it silently (its later recovery may reach the operator alone). The `Rebuild` inherit
  closes stay immediate (they close committed rows; a repeated recovery is the worst case). Nothing for the operator to clear.
- Pinned by `WarnAfterCommitTest` [verified: aether/node/src/test/java/org/pragmatica/aether/deployment/cluster/fsm2/WarnAfterCommitTest.java] (FSM-level, in-JVM harness; no multi-node failure injection).
