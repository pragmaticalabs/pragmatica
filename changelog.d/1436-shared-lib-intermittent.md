### Changed (2026-10-04 — #1436: a `[shared]` locate that cannot answer now fails `Intermittent`)
- A runtime-provided `[shared]` library now fails `Intermittent` while any configured repository cannot answer
  (e.g. an unreachable `remote:` repository); the default Local-only config is unaffected.
