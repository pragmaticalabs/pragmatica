### Changed (2026-11-18 — Fsm2Leader TLA+ model at the #3274 warning feed, #3311)
- **TLA+ extractor** (`tools/tla/extract.py`) now refuses a module that references an operator or capitalised name no
  fragment defines, naming the Java line (the release-branch shape that applied a removed `Notify(n)` now fails
  extraction); `selftest.sh` probes it.
- **Fsm2Leader model** (embedded in `cluster/fsm2`) parses at the release tip and describes the #3274 code: rule O reads
  the row's writer stamp (`Warnings.own`), and a new action property `NoBlindTombstone` (an accepted WARNINGS write never
  tombstones a subject whose condition holds unless the writer observed it close) pins rule O and `inherit`.
  The vacuous `PublishedIsCommitted` properties are removed (nothing is held since #3274).
- **models.tsv**: 41 Fsm2Leader rows, each stamped with its TLC verdict at the extracted spec (0 unexpected). New mutant
  rows `mut_noinherit_blind`, `mut_noruleo_blind`, `mut_noruleo_resume`; rows that could not discriminate (the LATE=TRUE
  duplicates `code_close/show/persist/agree`, `code_lag_inv`, `mut_noreplay_show`, `mut_noinherit_close`) are removed with
  the reason recorded. `code_nolate_close/show/persist` hold exhaustively at MaxCond=2 (their `small` profile); at the
  written MaxCond=3 they are bounded, not exhaustive.
