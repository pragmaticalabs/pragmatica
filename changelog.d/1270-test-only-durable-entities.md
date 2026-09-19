### Changed (2026-09-19 — #1270: test-only durable-entity implementations shipped in production sources)
- **`InMemoryDurableEntity` and `FencedDurableEntity` were compiled into the production
  `resource-durable-entity` artifact although only tests construct them.** Production provisioning
  builds only `PartitionFencedDurableEntity`. Both classes now live in the module's test sources, in
  the same package, so their tests are unchanged and the unfenced in-memory entity is absent from the
  shipped jar rather than merely package-private.
  [mechanism: `find aether -path '*/src/main/*'` for both file names returns nothing]
- The main-source comments naming them (`DurableEntityFactory`, `EntityError.TimerNotSupported`,
  `PerKeySerialExecutor`, the `test-entity` blueprint's `EntitySlice`) now describe them as
  test-only fixtures, and `aether/docs/reference/guarantees.md` (row 28 and the durable-entity
  section) no longer cites them as "package-private and unreachable".
