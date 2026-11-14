### Changed (2026-11-14 — Fsm2Leader TLA+ model brought to the #3175 marker mechanism)
- **Fsm2Leader model** (embedded in `cluster/fsm2`) describes the code after #3175: committed rows carry their unpublished
  opener/recovery markers, WARNINGS writes compare row values (CAS), publish skips while the leader value names another node,
  `inherit` and the late `onPut` path replay markers, and election is split from the `LeaderKey` commit so a successor
  activates suspended. A new `LATE` constant separates the #3203 ordering (late settle before the `LeaderKey` notification).
- **models.tsv**: `code_close/show/persist/agree` now pin the #3203 residual instead of #3160; new rows `code_nolate_*`,
  `code_lag_inv`, `pre3085A_close/agree`, `mut_noreplay_show`, `mut_nocas_agree`; `codeA_resume_*` are reachable only via
  #3253. Verdicts of the new and relabelled rows are pending a TLC run.
