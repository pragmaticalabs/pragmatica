### Changed (2026-09-26 — S28: KV backup key classification and binary-per-entry backup format)
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
- **New `BackupEntryCodec` replaces the hand-written `KVStoreSerializer` pipe grammar** (which had no
  production caller). A backup document is a four-line header (format version, revision, a cluster
  incarnation placeholder, entry count) followed by one line per entry: base64 of the value's generated
  wire codec, then the key's canonical string. Encoding keeps backed-up entries only and refuses keys
  that would not parse back to themselves; decoding refuses runtime keys, non-canonical keys, and values
  that do not re-encode to the stored bytes, and reports every bad line with its number as a `Result`
  failure, never an exception. Every backed-up key type must have a byte-exact round-trip fixture
  [verified: `aether/slice/src/test/java/org/pragmatica/aether/slice/kvstore/BackupEntryCodecTest.java`] —
  unit-level only; no restore path uses it yet [design intent — unverified].
  **Known gap:** an operator's scheduled-task pause lives in the runtime `ScheduledTaskValue`, so a
  cold restart loses it.
- **Removed dead KV types** `StorageBlockKey`/`StorageBlockValue`, `StorageRefKey`/`StorageRefValue`,
  `CloudCredentialsKey`/`CloudCredentialsValue` (no production writer or reader). Their `SystemTags`
  pins stay, marked RETIRED, so the tags are never reused.
