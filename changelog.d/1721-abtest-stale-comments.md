### Docs (2026-10-04 — comments in `AbTestManager` and its floor test no longer describe the canary as one instance)
- Comment-only follow-up to #1721: the variant runs at the slice floor (3), so the remarks about "one instance" and
  the `target = 1, min = 5` example are corrected. No behaviour change.

### Changed (2026-10-04 — #1436 behaviour note)
- A `[shared]` library that is not found by any repository is still registered runtime-provided, but a `[shared]`
  locate now fails `Intermittent` (the slice load is retried) while ANY configured repository cannot answer,
  instead of silently registering the library. The default Local-only configuration is unaffected.
