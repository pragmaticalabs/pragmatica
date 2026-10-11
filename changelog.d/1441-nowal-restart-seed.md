### Fixed (2026-10-03 — #1441: a no-WAL restart re-assigned offsets the durable tier already held)
- **Stream recovery seeded the fresh ring only inside WAL replay.** A partition without a WAL restarted at
  head -1, so its next publish was assigned offset 0 even when segments `0..N` were already sealed. Two
  different records then shared one offset. Sealing does not depend on the WAL, so the collision was real.
  The no-WAL state is reachable when the WAL directory (an explicit `wal_path`, or the derived `wal`
  subdirectory) is unwritable while `segments/` is writable: a node built outside `Main` (Forge, Ember,
  embedded) degrades to no WAL with a WARN, and `Main` admits it only under `aether.allowNonDurableStreams`.
- Recovery now follows one rule on every path: the next offset is the highest durable offset + 1. The ring is
  seeded at the rebuilt sealed floor, with or without a WAL, and a WAL tail above it is placed on top.
- **After a crash, everything above the last metadata snapshot was in scope, not just the un-sealed tail.** The
  floor is rebuilt from the streams metadata snapshot on disk, which runs behind the live refs (up to 100
  mutations / 30 s). With a WAL the WAL still holds that range. Without one, a crash re-assigned every offset
  sealed since the last snapshot. A seal without a WAL now resolves only once a snapshot holding its ref is on
  disk, so the floor covers every completed seal. A no-WAL crash still loses the records above the last
  completed seal, which never reached the durable tier, and their offsets are assigned again.
  Cost: one metadata snapshot per seal, on no-WAL partitions only. While that snapshot cannot be written, those
  seals fail and are retried.
- **A consumer resumed from a cursor above the partition head silently skipped records.** After such a restart
  (or a promotion that kept less), the stored cursor can sit above the head, and the consumer would wait for the
  head to reach it, never delivering the records assigned in between. The resume now clamps the cursor to the
  head + 1, asked of the partition owner, and WARNs with both offsets. Delivery is at-least-once, so the clamp
  can only redeliver.
- Pinned by `StreamNoWalRestartOffsetTest` (including the crash case, a snapshot that predates the seals),
  `StorageSegmentSinkTest$RefDurabilityOnSeal` and `StreamConsumerRuntimeTest$ResumeAboveHead`.
  `[unverified: neither induced on a running node]`. `[unverified: a consumer that keeps running while its
  partition's owner restarts below its cursor is not clamped; the check runs only at resume]`.
