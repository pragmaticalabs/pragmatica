### Changed (2026-11-24 — #3587: the leader-admission model's Fixed twin of `interleave`)
- **`LeaderAdmission` row `interleavefixed`** (`tools/tla/models.tsv`, pinned in `pinned-counts.tsv`): the constants of the known-bad rows `interleave`,
  `interleavelearns` and `interleavemono` with the fix `serial` on (every leader event of a node in one serial executor, #3605); it must pass
  `PassiveInView`, `PassiveLearns` and `AdSeqMono`, while the three known-bad rows (the fix removed) must each still fail their named property.
- The `P_Push` fragment now sits on `LeaderElectionContext.submit`, the one delivery of every leader event (a pushed commit and every view write) onto the node's serial drainer, so the
  fragment-touched check sees an edit of that delivery [verified: touched.py RED when submit's serialisation is removed]. It ties exactly that: the admission being one task atomic against view writes. It does not tie the view-write entries in
  `LeaderManager` (they reach `submit` or `serially`, outside any fragment region), nor `SerialExecutor` itself [unverified by the model; covered by `LeaderSerialDispatchCensusTest` and `PassiveAdmissionInterleaveTest`].
  `LeaderElectionContext.java` joins `tla-models.yml` paths in place of `LeaderManager.java` (which no longer holds a fragment).
