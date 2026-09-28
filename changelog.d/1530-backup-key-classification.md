### Changed (2026-09-27 — #1530: KV backup key classification)
- **Which KV keys a cold-restart backup holds was a hand-maintained list that a new key could silently
  miss.** Every `AetherKey` now declares itself `AetherKey.ClusterStateKey` or `AetherKey.RuntimeKey`;
  `AetherKey` permits nothing else, so a key that does neither does not compile
  `[mechanism: sealed interface AetherKey permits ClusterStateKey, RuntimeKey]`. `ConfigKey` backs up
  only cluster-wide entries (`isBackedUp()` is false for node-scoped overrides). `EphemeralKeys` is
  deleted. Reclassified to runtime: `TopicSubscriptionKey`, `EntityKeyspaceRegistrationKey`,
  `StreamRegistrationKey`, `ConsumerAssignmentKey`, `StreamCursorCheckpointKey`,
  `ScheduledTaskStateKey`, `StreamRegistryKey`, `ScheduledTaskKey`, `WorkerSliceDirectiveKey`,
  `GossipKeyRotationKey`. `EntityCheckpointKey` stays backed up (owner ruling): the checkpoint pointer
  is carried in the backup and is to be validated at restore — applied only if its block exists and the
  local log covers its offset (#1533). `BackupKeyClassificationTest` fails if any backed-up key or value can reach a
  `NodeId` outside a commented allowlist — a unit-level pin; no restore path exists yet
  `[design intent — unverified]`.
- **Node-scoped `ConfigKey` now renders as `config-node/<nodeId>/<key>`** (was `config/node/<nodeId>/<key>`),
  so a cluster-wide key named `node/...` can no longer render to — or be parsed back as — a node-scoped
  one; the two prefixes differ at their first segment (pre-GA wire break of the string form; the binary
  codec is unchanged).
- **Removed dead KV types** `StorageBlockKey`, `StorageRefKey`, `CloudCredentialsKey`,
  `StreamMetadataKey`, `AbTestRoutingKey` and their values (no production writer or reader; the
  `StreamMetadataKey` writer was an uncalled `@SuppressWarnings("unused")` helper, removed with its no-op
  hook). Their `SystemTags` pins stay, marked RETIRED, so the tags are never reused.
- **The operator's scheduled-task pause is now cluster state.** It moved out of the runtime
  `ScheduledTaskValue` (component removed — a wire-shape change) into a new
  `ScheduledTaskPauseKey`/`ScheduledTaskPauseValue` (tags 1707/1708): pause writes the key, resume
  removes it, and `ScheduledTaskRegistry` derives each task's `paused` from it — including a pause that
  arrives before its task registers, which is what a restore produces. Republishing a task no longer
  touches the pause; the last replica's unpublish removes both. Pinned by unit tests in
  `ScheduledTaskManagerTest` (pause survives an encode→decode→apply restore; resume clears it)
  `[design intent — unverified]` on a live cluster.
- **Limitations of this classification after a restore** (by reading, `[design intent — unverified]`):
  - operator-created streams lose their `StreamRegistryKey` catalog entry (runtime), and nothing yet
    rebuilds it from the backed-up `StreamConfigKey` — that rebuild belongs to #1533;
  - `GossipKeyRotationKey` is excluded by ruling, so a cold restart reverts an emergency gossip-key
    rotation; keys are regenerated on restore;
  - consumer-group cursors (`StreamCursorCheckpointKey`, runtime) are lost, so groups restart from
    their `autoOffsetReset` position.
