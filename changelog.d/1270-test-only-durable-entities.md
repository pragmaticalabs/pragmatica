### Changed (2026-09-19 — #1270: test-only durable-entity implementations shipped in production sources)
- **`InMemoryDurableEntity` and `FencedDurableEntity` were compiled into the production
  `resource-durable-entity` artifact although only tests construct them.** Production provisioning
  builds only `PartitionFencedDurableEntity`. Both classes now live in the module's test sources, in
  the same package, so their tests are unchanged and the unfenced in-memory entity is absent from the
  shipped jar rather than merely package-private.
  [mechanism: `find aether -path '*/src/main/*'` for both file names returns nothing; the built jar
  lists 0 entries for either class and 5 for `PartitionFencedDurableEntity` as control (review of
  `449f568c1`)]
- The main-source comments naming them (`DurableEntityFactory`, `EntityError.TimerNotSupported`,
  `PerKeySerialExecutor`, the `test-entity` blueprint's `EntitySlice`) now describe them as
  test-only fixtures, and `aether/docs/reference/guarantees.md` (row 28 and the durable-entity
  section) no longer cites them as "package-private and unreachable".
- The `DurableEntity` and `ReadConsistency` javadoc describes the shipped fenced-log implementation
  instead of the in-memory fixture: `BOUNDED_STALE` is the partition fold on any holder, caught up to
  the local log's head; `LINEARIZABLE` is owner-only and refuses a non-owner with `NotCurrentOwner`;
  `scheduleTimer`/`cancelTimer` are fenced log writes, so production never answers `TimerNotSupported`.
