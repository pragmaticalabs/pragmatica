### Changed (2026-09-27 — #1530: KV backup key classification)
- **Which KV keys a cold-restart backup holds was a hand-maintained list that a new key could silently
  miss.** Every `AetherKey` now declares itself `AetherKey.ClusterStateKey` or `AetherKey.RuntimeKey`;
  `AetherKey` permits nothing else, so a key that does neither does not compile
  `[mechanism: sealed interface AetherKey permits ClusterStateKey, RuntimeKey]`. `ConfigKey` backs up
  only cluster-wide entries (`isBackedUp()` is false for node-scoped overrides). `EphemeralKeys` is
  deleted. Reclassified to runtime: `TopicSubscriptionKey`, `EntityKeyspaceRegistrationKey`,
  `StreamRegistrationKey`, `ConsumerAssignmentKey`, `StreamCursorCheckpointKey`,
  `ScheduledTaskStateKey`, `StreamRegistryKey`, `ScheduledTaskKey`, `WorkerSliceDirectiveKey`,
  `GossipKeyRotationKey`. `BackupKeyClassificationTest` fails if any backed-up key or value can reach a
  `NodeId` outside a commented allowlist — a unit-level pin; no restore path exists yet
  `[design intent — unverified]`.
- **Removed dead KV types** `StorageBlockKey`, `StorageRefKey`, `CloudCredentialsKey`,
  `StreamMetadataKey`, `AbTestRoutingKey` and their values (no production writer or reader; the
  `StreamMetadataKey` writer was an uncalled `@SuppressWarnings("unused")` helper, removed with its no-op
  hook). Their `SystemTags` pins stay, marked RETIRED, so the tags are never reused.
- **Limitations of this classification after a restore** (by reading, `[design intent — unverified]`):
  - operator-created streams lose their `StreamRegistryKey` catalog entry (runtime), and nothing yet
    rebuilds it from the backed-up `StreamConfigKey` — that rebuild belongs to #1533;
  - `GossipKeyRotationKey` is excluded by ruling, so a cold restart reverts an emergency gossip-key
    rotation; keys are regenerated on restore;
  - an operator's scheduled-task pause lives in the runtime `ScheduledTaskValue` and is lost;
  - consumer-group cursors (`StreamCursorCheckpointKey`, runtime) are lost, so groups restart from
    their `autoOffsetReset` position.
