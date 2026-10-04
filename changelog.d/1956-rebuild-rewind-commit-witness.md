### Fixed (2026-10-05 — #1956: a projection rebuild reported a committed rewind as refused)
- **`rebuild()` could answer `RewindNotCommitted` for a rewind that DID commit.** The rewind record's own
  apply restarts the consumer, and that consumer's first checkpoint at the same epoch could commit before
  the rewinder's read-back, which then no longer saw its record ("the cluster cursor carries epoch 0/1/1",
  the token's own epoch). Acceptance is now witnessed through the applier's accepted-put notification,
  registered before the put, with the read-back kept as the fallback; a refused put still emits nothing
  and is still reported. [mechanism: the KV applier dispatches ValuePut only for an accepted put, inside its apply;
  pinned in-JVM on the real applier by `DurableProjectionRebuildTest.rewindCommitted_thenOvertaken…`, not
  multi-node]
