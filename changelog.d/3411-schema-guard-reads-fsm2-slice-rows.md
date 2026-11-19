### Fixed (2026-11-19 — #3411: the schema migrate guard was not enforced under the default engine)
- **`aether schema migrate` re-armed a COMPLETED schema while its slices were serving.** The guard counted serving
  slices from `SliceNodeKey` rows, which no engine writes (the default FSM2 engine writes `SliceObservedKey` rows, the
  legacy engine writes `NodeArtifactKey` rows), so under either engine the count was always 0 and the re-arm was
  accepted. The guard, the schema status `heldSlices` list and `GET /api/nodes` now read the rows the running engine
  writes (`SliceObservedKey` under FSM2, `NodeArtifactKey` under the legacy engine) through one shared read; the
  legacy-engine readers were reading nothing and are fixed too. "Serving" has one definition (`SliceState.isServing`:
  ACTIVATING, ROUTING and ACTIVE), over-inclusive on purpose: a false refusal costs the operator a retry. The refusal
  (HTTP 409) names the serving instance count; when a re-migration is genuinely intended, `aether schema baseline` or
  `undo` first, as the refusal says. `TopologyCollector` (no production caller) was only kept consistent.
  [verified: aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/SchemaMigrateGuardFsm2Test.java]
- `GET /api/nodes` again lists a node known only by the slices it hosts.
