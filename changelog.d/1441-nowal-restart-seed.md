### Fixed (2026-10-03 — #1441: a no-WAL restart re-assigned offsets the durable tier already held)
- **Stream recovery seeded the fresh ring only inside WAL replay.** A partition without a WAL restarted at
  head -1, so its next publish was assigned offset 0 even when segments `0..N` were already sealed. Two
  different records then shared one offset. Sealing does not depend on the WAL, so the collision was real.
  The no-WAL state is reachable: any node built outside `Main` (Forge, Ember, embedded) degrades to no
  WAL when its WAL directory is unwritable, and `Main` admits it under `aether.allowNonDurableStreams`.
- Recovery now seeds the ring from the last-sealed offset on every path, with or without a WAL.
  `StreamNoWalRestartOffsetTest` pins it: after a no-WAL restart over a floor sealed through 2, the next
  offset is 3.
