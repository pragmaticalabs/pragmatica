### Fixed (2026-11-08 — #2875: a racing trigger could start a consumer pass inside a refusal hold)
- **A consumer whose read the transport refused could re-read at once instead of holding** (a first hold measured 0 ms):
  an append or poll tick that checked the hold just before the refused pass parked the loop won `running` the moment the
  pass released it. The delivery pass now re-checks the hold once it owns the loop and yields to the park if it is still
  running; the park's end re-drives it. Pinned by a test that refuses 10 000 consumers while appends hammer them.
