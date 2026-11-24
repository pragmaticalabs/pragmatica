### Changed (2026-11-24 — #3587: the leader-admission model's Fixed twin of `interleave`)
- **`LeaderAdmission` row `interleavefixed`** (`tools/tla/models.tsv`, pinned in `pinned-counts.tsv`): the constants of the known-bad rows `interleave`,
  `interleavelearns` and `interleavemono` with the fix `serial` on (every leader event of a node in one serial executor, #3605); it must pass
  `PassiveInView`, `PassiveLearns` and `AdSeqMono`, while the three known-bad rows (the fix removed) must each still fail their named property.
- The `P_Push` fragment now sits on `FsmBackedLeaderManager.onLeaderCommitted(leader, viewSequence)`, the serial task that makes the push's admission one step, so the
  fragment-touched check sees an edit of that entry point.
