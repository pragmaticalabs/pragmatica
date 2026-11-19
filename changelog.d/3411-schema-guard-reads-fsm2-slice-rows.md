### Fixed (2026-11-19 — #3411: the schema migrate guard was not enforced under the default engine)
- **`aether schema migrate` re-armed a COMPLETED schema while its slices were serving.** The guard counted serving
  slices from `SliceNodeKey` rows, which the default (FSM2) engine never writes, so the count was always 0 and the
  re-arm was accepted. The guard, the schema status `heldSlices` list, `GET /api/nodes` and the topology slice
  distribution now read the engine's own per-node slice rows (`SliceObservedKey` under FSM2, `NodeArtifactKey` under
  the legacy engine) through one shared read. "Serving" has one definition (`SliceState.isServing`: ACTIVATING,
  ROUTING and ACTIVE), over-inclusive on purpose: a false refusal costs the operator a retry. The refusal (HTTP 409)
  names the serving instance count; when a re-migration is genuinely intended, `aether schema baseline` or `undo`
  first, as the refusal says.
  [verified: aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/SchemaMigrateGuardFsm2Test.java]
- `GET /api/nodes` again lists a node known only by the slices it hosts.
