### Added (2026-10-12 — #2170: deployment state machine rewrite, phase 1: KV records and wire tags)
- **Five new runtime KV records and their values** for the deployment state machine rewrite: `SliceDesiredKey`/`SliceObservedKey` (the key split, one writer per key), `DeploymentApplyKey`, `ArtifactJudgmentKey` and `NodeDepartureKey`, with the nested `Generation` and `ProcessIncarnation`. They are new variants of the sealed `AetherKey` / `AetherValue`, a breaking wire change accepted pre-GA, and are excluded from backup.
- Nothing writes or reads them yet: this phase changes no behaviour. [mechanism: no production caller; `WorkerMetadataIndex.scopesFor` maps the new keys to no scope]
- All 16 new types are pinned in `SystemTags` (2143..2158); the wire-assignment baseline gained 32 lines and no other line changed.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/DeploymentFsmKvRecordsCodecTest.java`]
- The applier enforces the markers on the new values: a bare Put or Remove of a leader-authorized value is dropped, an observed value follows its version chain and is removed only by an equal witness.
  [verified: `aether/slice/src/test/java/org/pragmatica/aether/slice/kvstore/DeploymentFsmKvMarkersTest.java`]
