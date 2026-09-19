### Fixed (2026-09-19 — #1242: PerKeySerialExecutor never evicted per-key entries)
- **The durable-entity per-key serial executor kept one entry for every key it had ever seen**, each
  holding its last operation's resolved promise and therefore its result, so heap use grew with distinct
  entity keys × state size for the life of the entity resource. All three entity backings use it.
- An entry is now retired once its key's last operation resolves with nothing queued behind it. The
  decision is a compare-and-set of the key's tail to a retired sentinel, which a concurrent submit
  contends for on the same reference, so an operation submitted during retirement still runs strictly
  after its predecessor. A plain check-then-remove would not keep that order; the test for it forces the
  window open and fails against that form.
  [verified: aether/resource/durable-entity/src/test/java/org/pragmatica/aether/resource/entity/PerKeySerialExecutorTest.java]
  — unit level: 100,000 distinct keys leave 0 entries, and the ordering case is driven deterministically.
