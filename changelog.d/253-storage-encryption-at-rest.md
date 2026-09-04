### Added (2026-09-04 — #253: at-rest storage encryption keyed through the SecretsProvider SPI)
- **No production code path ever constructed a real `BlockEncryptor`.** The stream-segment pipeline
  (`StorageSegmentSink`/`SegmentReader`) defaults to `Option.empty()`, and the generic
  `StorageTier`/`StorageInstance` framework `StorageFactory` builds for `artifacts`, `content`, any
  `[storage.<name>]` instance, and `streams`' own block tiers had no encryption hook at all. Owner
  ruling (`know: a9d238140`): ship at-rest encryption via the *existing* `SecretsProvider` SPI
  (Vault/#119 stays deferred) — a keyring of named keys, one active key id for new writes, any key
  readable for old blocks, boot refuses an encrypted tier whose key can't be resolved.
- **New `[storage.encryption]` keyring**, resolved once at boot through the live `SecretsProvider`
  (bounded by a 30s timeout so a wedged secrets backend can't hang boot indefinitely), before any
  storage tier is constructed:
  ```toml
  [storage.encryption]
  active_key_id = "k1"
  streams_encrypted = false        # opts the built-in `streams` instance's segment-block tiers in

  [storage.encryption.keys]
  k1 = "${secrets:storage-key-v1}"
  k0 = "${secrets:storage-key-v0}"  # retired key, kept resolvable for reads only
  ```
  Each value is Base64-encoded AES-256 key material, resolved via the node's `SecretsProvider`
  (never inlined). Per-instance opt-in is `[storage.<name>] encrypted = true` (default false);
  `streams` has no `StorageConfig` of its own, so it uses the dedicated `streams_encrypted` flag
  instead. `StorageEncryptionConfigValidator` catches shape errors at config load — empty keyring,
  `active_key_id` absent from `keys`, a value not matching `${secrets:<path>}`, or `encrypted`/
  `streams_encrypted` requested with no `[storage.encryption]` section at all — before boot ever
  tries to resolve a secret.
- **New `EncryptingStorageTier`** (`integrations/storage`) wraps `LocalDiskTier`/`DhtStorageTier`
  transparently to `StorageInstance` — demotion, GC, and reads/writes all go through the unchanged
  `StorageTier.get/put` contract. Wire format is versioned and AES-GCM-authenticated:
  `MAGIC("AEC1") | VERSION | KEY_ID_LEN | KEY_ID | NONCE(12B) | CIPHERTEXT+TAG`, with everything
  before the nonce passed as AAD — editing the header, or swapping in a different (even validly
  encrypted) key id, fails decryption closed rather than silently decrypting under the wrong key.
- **Boot fails loudly, naming the key id, never the secret value**, when any configured key can't be
  resolved (malformed `${secrets:...}` reference, provider failure, bad Base64, wrong decoded
  length), when `active_key_id` isn't present in the resolved keys, or when `[storage.encryption]`
  is present but the node's environment has no `SecretsProvider` at all
  (`NoSecretsProviderForStorageEncryption`).
- **Legacy plaintext is refused, never silently accepted.** Enabling encryption over a local-disk
  tier whose directory already holds block files with no `.encryption-enabled` marker aborts boot
  (`EnablingOverExistingPlaintext`) rather than starting to write ciphertext alongside unreadable
  plaintext; a DHT tier has no directory to scan for that forward-direction check, so an unmarked
  plaintext block on the DHT tier is instead caught per block on read (`LegacyPlaintextBlock`)
  rather than returning raw bytes. The DHT tier does still have its own boot-time guard, in the
  opposite direction: both local-disk and DHT tiers write the same marker the first time encryption
  is enabled over them, and disabling encryption (or omitting the keyring) over a tier already
  marked encrypted is refused at boot (`EncryptedTierRequiresKeyring`), the same as for local disk.
  A block whose header names a key id absent from the node's keyring fails with `UnknownKeyId` —
  never a truncated or garbled read.
  **Limitation, not shipped here:** there is no migration path from an existing plaintext tier to
  an encrypted one; enabling encryption is new-instance/fresh-data only for rc4.
- **Key rotation:** add a key, flip `active_key_id`; every prior key stays in `keys` and remains
  readable. Re-encrypting existing blocks under the new active key (including on demote/promote) is
  an explicit follow-up, not part of this fix.
- **The synthesized default `artifacts` instance now tracks the keyring**, matching an explicit
  `[storage.artifacts] encrypted = true`: when `[storage.encryption]` is configured it is
  encrypted through the same path as any other named instance; with no keyring it stays plaintext,
  exactly as before. [verified: `StorageFactoryEncryptionTest#createAll_synthesizedDefaultArtifacts_isEncrypted_whenKeyringPresent`,
  `#createAll_synthesizedDefaultArtifacts_staysPlaintext_whenKeyringAbsent`]
- **What is NOT covered, verified against the actual boot wiring:**
  - `MemoryTier` — in-process only, never touches disk, deliberately never wrapped.
  - Metadata/refs/snapshot files (`MetadataStore`, `SnapshotManager`) for every instance including
    `streams` — these bypass `StorageTier` entirely and are written directly; only block payloads
    reached via `get`/`put` are encrypted.
  - The **`content` storage instance is architecturally unencryptable under this fix**: `AetherNode`
    always provisions it via `StorageFactory.defaultContentStorage`, a separate, keyring-less
    factory method that never routes through the config/keyring-aware `createAll`/`createOne` path
    — the same reason `content` already sits outside `storageSetups` for demotion/GC (#783). Not
    fixed here; flagged as the same underlying structural gap #783 tracks, not a new local patch.
    **Now surfaced, not silent:** when a node-wide keyring is configured, boot logs one WARN naming
    that the `content` instance is not covered, citing #783, so the gap is operator-visible instead
    of only discoverable by reading source. [verified:
    `AetherNodeContentStorageWarnBootTest#assembleNode_warnsOnContentStorageGap_whenKeyringConfigured`,
    exercised through the real `AetherNode.aetherNode(...)` boot path, not an extracted helper;
    `#assembleNode_staysSilentOnContentStorage_whenNoKeyringConfigured` pins the negative case]
  - `[streams.X].encryption-key-id` (the per-stream blueprint key) is unrelated to
    `streams_encrypted` above and remains the dead/rejected config `#576` already found inert
    (2026-08-27) — `StorageSegmentSink`'s own segment pipeline still has no encryptor wired to it.
    This fix does not change that; the two "streams encryption" surfaces name different mechanisms.
  [mechanism: `EncryptingStorageTier` AES-256-GCM, AAD over the versioned header, key resolved once
  at boot via `SecretsProvider` — `EncryptingStorageTierTest`, `EncryptionKeyringTest`,
  `BlockEncryptorTest` (`integrations/storage`); `StorageEncryptionConfigTest` (`aether/aether-config`);
  `StorageEncryptionTest`, `StorageFactoryEncryptionTest`, `AetherNodeStorageEncryptionBootTest`,
  `AetherNodeContentStorageWarnBootTest` (`aether/node`) — all mutation-probed: each production hunk
  was reverted, its named test confirmed red, then the file restored and the full suite reconfirmed
  green]
