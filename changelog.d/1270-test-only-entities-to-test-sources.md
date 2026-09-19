### Changed (2026-09-19 — #1270: test-only durable-entity implementations moved out of production sources)
- **`InMemoryDurableEntity` and `FencedDurableEntity` shipped in the production jar although only tests
  construct them.** A node provisions only `PartitionFencedDurableEntity`.
- Both classes now live in `aether/resource/durable-entity/src/test/java`, in the same package, beside
  the tests that use them. Main-side comments that named them as backings, and `guarantees.md` row 28,
  now say where they live.
  [mechanism: the module's built jar contains no `InMemoryDurableEntity` or `FencedDurableEntity` class,
  checked with `unzip -l` after a clean package; the module's test count is unchanged at 272]
