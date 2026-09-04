// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockEncryptor;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.EncryptionError;
import org.pragmatica.storage.EncryptionKeyring;
import org.pragmatica.storage.LocalDiskTier;

import static java.util.Arrays.copyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/// #253: proves `StorageFactory` wires [org.pragmatica.storage.EncryptingStorageTier] around the
/// tiers the per-instance config actually asks for -- and around no others.
///
/// `StorageInstance` exposes no `tiers()` accessor, so every assertion here is BEHAVIOURAL: write a
/// block through the assembled instance, then read the bytes the backing tier actually stored
/// (straight off the temp disk directory, or straight out of the fake DHT client's map) and compare
/// them against the plaintext. Byte-equal proves plaintext at rest; not-equal proves ciphertext.
/// Reading through the tier itself would decrypt transparently and prove nothing.
///
/// The raw-disk technique (and the `randomKey`/`singleKeyRing` helper shapes) mirror
/// `EncryptingStorageTierTest` in `integrations/storage`; those helpers are private to that class in
/// another module's test tree, so they are rewritten rather than reused -- the same duplication
/// precedent `StorageMaintenanceWiringTest`'s `InMemoryDHTClient` already records.
class StorageFactoryEncryptionTest {

    private static final byte[] PLAINTEXT = "storage-factory-plaintext-block-253".getBytes(StandardCharsets.UTF_8);
    private static final long MEMORY_MAX_BYTES = 8L * 1024 * 1024;
    private static final long DISK_MAX_BYTES = 64L * 1024 * 1024;
    private static final String INSTANCE = "vault";
    private static final String NODE_ID = "node-1";
    private static final String ARTIFACTS = "artifacts";

    @TempDir
    Path tempDir;

    private static byte[] randomKey() {
        var key = new byte[32];

        new SecureRandom().nextBytes(key);

        return key;
    }

    private static EncryptionKeyring singleKeyRing(String keyId) {
        return EncryptionKeyring.encryptionKeyring(Map.of(keyId, BlockEncryptor.aesGcm(randomKey(), keyId).unwrap()), keyId)
                                 .unwrap();
    }

    private StorageConfig storageConfigAt(Path diskPath, boolean encrypted) {
        return StorageConfig.storageConfig(MEMORY_MAX_BYTES,
                                            DISK_MAX_BYTES,
                                            diskPath.toString(),
                                            tempDir.resolve("snapshots").toString(),
                                            1000,
                                            "60s",
                                            5,
                                            "",
                                            encrypted);
    }

    /// Mirrors `LocalDiskTier`'s own private `blockPath` sharding (`{base}/{hex[0:2]}/{hex[2:4]}/{hex}`)
    /// so a test can inspect what landed on disk without going through the (possibly encrypting) tier.
    private static Path rawBlockPath(Path base, BlockId id) {
        var hex = id.hexString();

        return base.resolve(hex.substring(0, 2))
                   .resolve(hex.substring(2, 4))
                   .resolve(hex);
    }

    /// Seeds `dir` with a block written through the RAW, unwrapped disk tier -- i.e. exactly what a
    /// prior unencrypted boot of this instance would have left behind.
    private static void seedRawPlaintextBlock(Path dir) {
        LocalDiskTier.localDiskTier(dir, DISK_MAX_BYTES)
                     .unwrap()
                     .put(BlockId.blockId(PLAINTEXT).unwrap(), PLAINTEXT)
                     .await()
                     .onFailure(cause -> fail("seeding a raw plaintext block failed: " + cause.message()));
    }

    private static BlockId writeThrough(StorageFactory.StorageSetup setup) {
        return setup.instance()
                    .put(PLAINTEXT)
                    .await()
                    .onFailure(cause -> fail("put through the assembled instance failed: " + cause.message()))
                    .unwrap();
    }

    private static void assertCiphertextAtRest(byte[] stored, String where) {
        assertThat(stored).as("%s must hold ciphertext, not the plaintext block", where)
                          .isNotEqualTo(PLAINTEXT);
        assertThat(new String(stored, StandardCharsets.UTF_8))
                .as("%s must not contain the plaintext anywhere in the framed block", where)
                .doesNotContain(new String(PLAINTEXT, StandardCharsets.UTF_8));
    }

    private static void assertPlaintextAtRest(byte[] stored, String where) {
        assertThat(stored).as("%s must hold the block verbatim -- this instance did not opt into encryption", where)
                          .isEqualTo(PLAINTEXT);
    }

    @Test
    void createAll_encryptsDiskTier_whenInstanceConfigEncryptedAndKeyringPresent() throws IOException {
        var diskDir = tempDir.resolve("vault-disk");
        var setups = StorageFactory.createAll(Map.of(INSTANCE, storageConfigAt(diskDir, true)),
                                               NODE_ID,
                                               Option.none(),
                                               Option.some(singleKeyRing("key-1")));

        assertThat(setups).containsKey(INSTANCE);

        var blockId = writeThrough(setups.get(INSTANCE));

        assertCiphertextAtRest(Files.readAllBytes(rawBlockPath(diskDir, blockId)), "the disk tier");
    }

    /// The critical anti-regression pin, and the exact inverse of the test above: same keyring, same
    /// factory call, only `encrypted` flipped. Mutation target is `createOne`'s
    /// `var effectiveKeyring = config.encrypted() ? keyring : Option.<EncryptionKeyring>empty();` --
    /// drop that gate and mere keyring PRESENCE starts encrypting instances that never asked for it,
    /// which this test catches and the encrypted-case test above cannot.
    @Test
    void createAll_leavesDiskTierPlaintext_whenInstanceConfigNotEncrypted_evenWithKeyringPresent() throws IOException {
        var diskDir = tempDir.resolve("vault-disk-plain");
        var setups = StorageFactory.createAll(Map.of(INSTANCE, storageConfigAt(diskDir, false)),
                                               NODE_ID,
                                               Option.none(),
                                               Option.some(singleKeyRing("key-1")));

        assertThat(setups).containsKey(INSTANCE);

        var blockId = writeThrough(setups.get(INSTANCE));

        assertPlaintextAtRest(Files.readAllBytes(rawBlockPath(diskDir, blockId)), "the disk tier");
    }

    /// The synthesized default `artifacts` instance (no `[storage.artifacts]` in config) must stay
    /// plaintext even when the node carries a keyring, because `StorageConfig.storageConfig()`'s
    /// default has `encrypted() == false`.
    ///
    /// Deliberately asserted on the DHT tier rather than the disk tier: the synthesized default's
    /// `diskPath` is the fixed absolute `/data/aether/storage`, which is not creatable in a test
    /// sandbox, so `handleDiskTierUnavailable` degrades this instance to memory+DHT and there is no
    /// file to read. That degraded path is precisely where the coverage matters -- `maybeEncryptDht`
    /// applies the keyring on BOTH the disk-available and disk-unavailable branches, so an
    /// encryption gate removed from `createOne` shows up here as ciphertext in the DHT store
    /// regardless of whether the default disk path happens to be writable on the host.
    @Test
    void createAll_synthesizedDefaultArtifacts_isNeverEncrypted_evenWithKeyringPresent() {
        var dhtClient = new InMemoryDHTClient();
        var setups = StorageFactory.createAll(Map.of(),
                                               NODE_ID,
                                               Option.some(dhtClient),
                                               Option.some(singleKeyRing("key-1")));

        assertThat(setups).containsKey(ARTIFACTS);

        var blockId = writeThrough(setups.get(ARTIFACTS));
        var stored = dhtClient.rawValue("artifacts-blocks", blockId);

        assertThat(stored.isPresent()).as("the DHT tier is always present when a client is supplied, on both "
                                          + "the disk-available and the degraded memory+DHT path")
                                      .isTrue();
        stored.onPresent(raw -> assertPlaintextAtRest(raw, "the synthesized 'artifacts' DHT tier"));
    }

    /// `createAll` returns a plain `Map`, not a `Result`: a per-instance construction failure is
    /// logged and the instance is simply absent. Enabling encryption over a directory that already
    /// holds unmarked plaintext is such a failure (`EncryptingStorageTier#wrapLocalDisk` refuses
    /// rather than writing ciphertext alongside unreadable plaintext), so absence from the map IS
    /// the observable refusal.
    @Test
    void createAll_omitsInstance_whenDiskAlreadyHoldsPlaintextAndEncryptionRequested() {
        var diskDir = tempDir.resolve("vault-legacy-disk");

        seedRawPlaintextBlock(diskDir);

        var setups = StorageFactory.createAll(Map.of(INSTANCE, storageConfigAt(diskDir, true)),
                                               NODE_ID,
                                               Option.none(),
                                               Option.some(singleKeyRing("key-1")));

        assertThat(setups).as("an instance whose encryption enablement was refused must not be silently "
                              + "created unencrypted -- it must be absent")
                          .doesNotContainKey(INSTANCE);
    }

    /// Mutation target: the `keyring.fold(() -> Result.success(defaultStreamStorage(...)), ...)`
    /// branch selection in the four-arg overload. With no keyring it must delegate to the plain
    /// three-arg overload, byte-for-byte unchanged from pre-#253 behaviour.
    @Test
    void defaultStreamStorage_delegatesToPlaintextBehavior_whenKeyringAbsent() throws IOException {
        var streamDataDir = tempDir.resolve("streams-plain");
        var setup = StorageFactory.defaultStreamStorage(Option.none(), streamDataDir, NODE_ID, Option.none())
                                   .onFailure(cause -> fail("the unencrypted overload must not fail: " + cause.message()))
                                   .unwrap();

        var blockId = writeThrough(setup);

        assertPlaintextAtRest(Files.readAllBytes(rawBlockPath(streamDataDir.resolve("segments"), blockId)),
                              "the streams segments dir");
    }

    @Test
    void defaultStreamStorage_encryptsDiskTier_whenKeyringPresent() throws IOException {
        var streamDataDir = tempDir.resolve("streams-encrypted");
        var setup = StorageFactory.defaultStreamStorage(Option.none(),
                                                         streamDataDir,
                                                         NODE_ID,
                                                         Option.some(singleKeyRing("key-1")))
                                   .onFailure(cause -> fail("the encrypted overload must succeed on a fresh dir: " + cause.message()))
                                   .unwrap();

        var blockId = writeThrough(setup);

        assertCiphertextAtRest(Files.readAllBytes(rawBlockPath(streamDataDir.resolve("segments"), blockId)),
                               "the streams segments dir");
    }

    /// Unlike `createAll`, the four-arg `defaultStreamStorage` returns a `Result`, so the same
    /// refusal surfaces to the caller as a typed failure rather than as an absent map entry.
    @Test
    void defaultStreamStorage_fails_whenSegmentsDirAlreadyHoldsPlaintext_andKeyringPresent() {
        var streamDataDir = tempDir.resolve("streams-legacy");

        seedRawPlaintextBlock(streamDataDir.resolve("segments"));

        StorageFactory.defaultStreamStorage(Option.none(), streamDataDir, NODE_ID, Option.some(singleKeyRing("key-1")))
                       .onSuccess(_ -> fail("enabling stream encryption over an existing plaintext segments dir must fail"))
                       .onFailure(cause -> assertThat(cause).isInstanceOf(EncryptionError.EnablingOverExistingPlaintext.class));
    }

    /// The design calls for a decorator over the LocalDisk AND DHT tiers; tests 1-2 only cover disk.
    /// This pins the DHT half: the block's durable copy (the DHT tier is last, hence the durable
    /// tier, when a client is present) must be ciphertext in the backing store.
    @Test
    void createAll_encryptsDhtTier_whenInstanceConfigEncryptedAndKeyringPresent() {
        var dhtClient = new InMemoryDHTClient();
        var setups = StorageFactory.createAll(Map.of(INSTANCE, storageConfigAt(tempDir.resolve("vault-dht-disk"), true)),
                                               NODE_ID,
                                               Option.some(dhtClient),
                                               Option.some(singleKeyRing("key-1")));

        assertThat(setups).containsKey(INSTANCE);

        var blockId = writeThrough(setups.get(INSTANCE));
        var stored = dhtClient.rawValue(INSTANCE + "-blocks", blockId);

        assertThat(stored.isPresent()).as("write-through must reach the DHT tier -- an empty backing store means "
                                          + "the tier never made it into the assembled tier list")
                                      .isTrue();
        stored.onPresent(raw -> assertCiphertextAtRest(raw, "the DHT tier"));
    }

    /// Plaintext counterpart to the test above, closing the DHT pair the way tests 1-2 close the
    /// disk pair: without it, "DHT holds ciphertext" is also satisfied by a factory that encrypts
    /// every DHT tier unconditionally.
    @Test
    void createAll_leavesDhtTierPlaintext_whenInstanceConfigNotEncrypted_evenWithKeyringPresent() {
        var dhtClient = new InMemoryDHTClient();
        var setups = StorageFactory.createAll(Map.of(INSTANCE, storageConfigAt(tempDir.resolve("vault-dht-plain"), false)),
                                               NODE_ID,
                                               Option.some(dhtClient),
                                               Option.some(singleKeyRing("key-1")));

        assertThat(setups).containsKey(INSTANCE);

        var blockId = writeThrough(setups.get(INSTANCE));
        var stored = dhtClient.rawValue(INSTANCE + "-blocks", blockId);

        assertThat(stored.isPresent()).isTrue();
        stored.onPresent(raw -> assertPlaintextAtRest(raw, "the DHT tier"));
    }

    /// In-memory `DHTClient` stub backed by a `ConcurrentHashMap`, plus a raw-value accessor so a
    /// test can read what the DHT tier actually stored without decrypting through it. Mirrors
    /// `StorageMaintenanceWiringTest.InMemoryDHTClient` -- duplicated for the same reason recorded
    /// there: package-private test doubles are not reusable across files.
    private static final class InMemoryDHTClient implements DHTClient {
        private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();

        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            return Promise.success(option(store.get(keyString(key))).map(v -> copyOf(v, v.length)));
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            store.put(keyString(key), copyOf(value, value.length));
            return Promise.success(unit());
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            return Promise.success(store.remove(keyString(key)) != null);
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return Promise.success(store.containsKey(keyString(key)));
        }

        /// Never invoked on the storage-tier path (`DhtStorageTier` only calls get/put/remove/exists).
        /// Returns null to match the shape the sibling stub in `StorageMaintenanceWiringTest` already
        /// established rather than introducing a second convention for the same unreachable method.
        @Override
        public Partition partitionFor(byte[] key) {
            return null;
        }

        /// `DhtStorageTier`'s key layout: `<prefix>/<blockId hex>`.
        Option<byte[]> rawValue(String keyPrefix, BlockId id) {
            return option(store.get(keyPrefix + "/" + id.hexString()));
        }

        private static String keyString(byte[] key) {
            return new String(key, StandardCharsets.UTF_8);
        }
    }
}
