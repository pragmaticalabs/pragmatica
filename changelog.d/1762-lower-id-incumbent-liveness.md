### Fixed (2026-09-30 — QUIC: a receipt-silent lower-id incumbent link was never superseded; refs #1758)
- **A silent lower-id incumbent no longer answers every redial with DUPLICATE.** `PeerState` kept an incumbent initiated by the
  lower id against any higher-id handshake, however long its peer had been silent, so when one end evicted a link and the
  other never saw the close, every redial was refused for a full liveness TTL (~15s in the S' re-run, blocking #1555 owner
  activation). A lower-id incumbent now survives only while younger than the receipt floor (so both ends keep the same link in a
  delayed-handshake race) or while its peer has been heard from within `max(3s, 3 x pingInterval)`; otherwise the verified fresh handshake supersedes it. The same floor now applies to
  the higher-id branch.
  [verified: `PeerStateTest` (silent supersedes, recently heard stays, race window keeps, floor scales with the ping
  interval; each reddens under its mutation), consensus 979 tests green.]
  [unverified: no cloud run; the 500ms window and the 3x floor are guesses.]
