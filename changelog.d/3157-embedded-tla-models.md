### Added (2026-11-13 — #3157: embedded TLA+ models with an on-demand model-check job)
- **Protocol models now live next to the code they describe**: SharedPools (#2978) and the owner catch-up stalled warning (#2967) are TLA+ specs in doc comments, extracted and checked by `tools/tla/` against an expected verdict per config (`tools/tla/models.tsv`). Results are bounds over the stated constants, not proofs.
- Known-bad configs must fail in every run; an expected failure cites an open issue (#3155, #3156) and flips to `pass` when it is fixed.
- `SharedPools` gains a test-only trace hook; with no sink installed each region runs its body and nothing else. `SharedPoolsTraceTest` checks a recorded run against the spec.
- `.github/workflows/tla-models.yml` runs on `workflow_dispatch` only; it is not a required check.
