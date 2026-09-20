### Fixed (2026-09-20 — #1352: a consumer read could be served an offset above the visible position from the durable tier)

- **The cold read was not bounded by visibility.** DROP_OLDEST seals every evictee — acknowledged or
  not: the WAL and the replicas hold it, and its publisher was told at most that the outcome is
  unknown, so the event may be in the log. The durable tier can therefore hold offsets above the
  partition's visible position. A consumer read that missed the ring (`CursorExpired`) fell through to
  the tier unbounded, so with min-sync 2 and a peer that had acknowledged only offset 0, a read from 1
  was served offset 1 from the tier while the ring refused the same offset.
- **`PartitionedStreamAccess` now bounds the tier read by the partition's visible position exactly as
  the ring read is bounded:** an offset above visible reads as `[]` (the ring's not-yet-visible shape,
  `OffHeapRingBuffer.readChecked`), and a read from below visible asks the tier for no more than
  `[fromOffset, visible]`. Sealing is unchanged: every evictee is sealed, and a sealed offset becomes
  readable from the tier once visible reaches it.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/TieredReadVisibleBoundTest.java`
  — the N3 shape (offset 0 sealed with nothing visible reads `[]`), the exposure (tier holds 1 above
  visible 0: read from 1 → `[]`, read from 0 → `[0]`), and the positive control (visible 3 → `[1, 2, 3]`)]
  [unverified: the internal entity-log fold (`StreamEntityLogSubstrate`) reads the tier unbounded by
  design — it reads the ring to the APPENDED head too — and is not a consumer read]
