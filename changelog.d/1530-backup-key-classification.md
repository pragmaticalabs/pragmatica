### Changed (2026-09-26 — #1530: KV backup key classification)
- **Which KV keys a cold-restart backup holds was a hand-maintained list that a new key could silently
  miss.** Every `AetherKey` now declares itself `AetherKey.ClusterStateKey` or `AetherKey.RuntimeKey`;
  `AetherKey` permits nothing else, so a key that does neither does not compile
  [mechanism: `sealed interface AetherKey permits ClusterStateKey, RuntimeKey`]. `ConfigKey` backs up
  only cluster-wide entries (`isBackedUp()` is false for node-scoped overrides). `EphemeralKeys` is
  deleted. Reclassified to runtime: `TopicSubscriptionKey`, `EntityKeyspaceRegistrationKey`,
  `StreamRegistrationKey`, `ConsumerAssignmentKey`, `StreamCursorCheckpointKey`,
  `ScheduledTaskStateKey`, `StreamRegistryKey`, `ScheduledTaskKey`, `WorkerSliceDirectiveKey`,
  `GossipKeyRotationKey`. A reflective guard fails if any backed-up key or value can reach a `NodeId`
  outside a commented allowlist [verified: `aether/slice/src/test/java/org/pragmatica/aether/slice/kvstore/BackupKeyClassificationTest.java`].
- **Removed dead KV types** `StorageBlockKey`/`StorageBlockValue`, `StorageRefKey`/`StorageRefValue`,
  `CloudCredentialsKey`/`CloudCredentialsValue` (no production writer or reader). Their `SystemTags`
  pins stay, marked RETIRED, so the tags are never reused.
