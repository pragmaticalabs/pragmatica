### Fixed (2026-10-04 — #1395: the durable-entity fold chased replay and catch-up batches recursively)
- **`EntityFold` replay and catch-up no longer nest stack frames per batch.** `replayBatch -> applyBatch -> replayBatch` (and
  the catch-up pair) ran the next batch's continuation inline whenever a read settled synchronously, about 7 frames per
  batch, so depth grew with history length divided by batch size and nothing pinned it (the sibling of #1392, which
  overflowed a 1 MB stack in `SegmentReader`). Both chases are now one loop (`chaseBatches`): a settled read is consumed
  in place, a pending one suspends and resumes. [mechanism: `EntityFoldBatchChaseDepthTest` asserts the maximum stack depth at
  a read over 2,000 one-record batches is within 60 frames of 20 batches, for both the rebuild replay and the catch-up.]
