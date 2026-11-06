### Fixed (2026-11-06 — #2759: FSM2 leader carry-forward, CF-1/4/5/6)
- **A blueprint deleted while its slice target was still being written could leave that target behind (FSM2).** A target
  put that committed after the delete had nothing left to remove it. Every target put now carries a read witness on its
  owner blueprint at the value the mirror held when the batch was sent (on an awaited-target re-send, the mirror's value at
  re-send time), so the applier refuses a put that would land after the delete or after a republish, in either commit order
  and across a leader change. A target that committed before the delete is removed by the delete's removal batch only if that
  batch commits: a removal batch lost with its leader's term still leaves an orphan target, pinned by a tripwire and closed by
  CF-8. `[mechanism: ReadWitness on AppBlueprintKey in the TARGETS transaction]`
- **A LOADED slice whose ACTIVE goal write failed or was lost stayed LOADED (FSM2).** Activation fired only on the LOADED
  observation. Reconcile now re-derives it (step 2c), so the goal ACTIVE commits within three ticks once writes commit
  again. This also covers a LOADED row that arrived while the leader could not submit.
- Pinned on the pure harness: the publish-then-delete orders, the cross-term put, two publishes of one id, the
  ACTIVATE re-issue arms, the deposed-rollback scenarios ported from #2593, and a source scan that only `Outbox` builds a
  `KVCommand` transaction.
