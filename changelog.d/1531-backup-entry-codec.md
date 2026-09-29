### Changed (2026-09-27 — #1531: binary per-entry KV backup format)
- **New `BackupEntryCodec` replaces the hand-written `KVStoreSerializer` pipe grammar** (which had no
  production caller). A backup document is a five-line header (format version, revision, a cluster
  incarnation placeholder, entry count, and a SHA-256 over every other line) followed by one line per
  entry: base64 of the value's generated wire codec written canonically — the bytes the KV snapshot and
  persisted command log carry — then the key's canonical string.
- Encoding keeps backed-up entries only and refuses a key that would not parse back to itself or a value
  of a type its key does not hold. Decoding accepts only the canonical rendering (the decoded state must
  re-encode to the identical document), verifies the checksum, binds each key type to its value type,
  refuses runtime and non-canonical keys, and reports every bad entry line by line number as a `Result`
  failure, never an exception. These properties are pinned by unit tests with mutation probes
  (`BackupEntryCodecTest`); no restore path consumes the format yet `[design intent — unverified]`.
- **Limitations inherited from the key classification (#1530)** (by reading, `[design intent — unverified]`):
  a restore does not bring back operator-created streams' `StreamRegistryKey` catalog entries (rebuild
  from `StreamConfigKey` belongs to #1533), and it reverts any emergency gossip-key rotation because
  `GossipKeyRotationKey` is excluded and regenerated on restore.
