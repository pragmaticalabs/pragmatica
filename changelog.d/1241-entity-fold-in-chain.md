### Fixed (2026-09-19 — #1241: durable entity fold applied in an async onSuccess outside the per-key chain)
- **A durable entity write was applied to the in-memory fold by an asynchronous `onSuccess` handler**, so
  the key's serialization tail — and the key's next operation — could run before the fold held the
  write, and a delayed apply of an older record could overwrite a newer record's state for the same key
  with nothing ever re-applying the newer one.
- The append path now applies the record as a synchronous step of the append's promise chain
  (`withSuccess`), before the chain continues. The fold still takes a record that reached the log when
  the write missed its replication barrier; that apply was already a synchronous step in `mapError`.
  [verified: aether/resource/durable-entity/src/test/java/org/pragmatica/aether/resource/entity/PartitionFencedDurableEntityApplyTest.java]
  — an in-JVM test with a stub substrate; no multi-node run.
- `EntityFold` now refuses to let an older offset overwrite a key that holds a newer one: the check and
  the write happen together under a per-key `compute`, re-checking the watermark as well. A superseded
  record is still counted towards the watermark. Per-key offsets are dropped once the watermark covers
  them, so the guard does not keep an entry for every key.
  [verified: aether/resource/durable-entity/src/test/java/org/pragmatica/aether/resource/entity/EntityFoldTest.java]
  (`StaleApply`) — unit level.
