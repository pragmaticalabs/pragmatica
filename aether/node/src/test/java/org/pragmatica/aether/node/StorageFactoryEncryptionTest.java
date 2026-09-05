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
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.BlockEncryptor;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.EncryptingStorageTier;
import org.pragmatica.storage.EncryptionError;
import org.pragmatica.storage.EncryptionKeyring;
import org.pragmatica.storage.LocalDiskTier;

import static java.util.Arrays.copyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

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
///
/// #253 BLOCKING #1 (2026-09-04 ruling): `createAll` now returns `Result<Map<String, StorageSetup>>`
/// instead of a plain `Map` -- every call site below that expects success unwraps the `Result`
/// (mirroring how `defaultStreamStorage`'s four-arg overload was already tested), and
/// [#createAll_fails_whenDiskAlreadyHoldsPlaintextAndEncryptionRequested] replaces the old
/// `createAll_omitsInstance_...` test that pinned the pre-ruling drop-and-continue behaviour.
class StorageFactoryEncryptionTest {

    private static final byte[] PLAINTEXT = "storage-factory-plaintext-block-253".getBytes(StandardCharsets.UTF_8);
    private static final long MEMORY_MAX_BYTES = 8L * 1024 * 1024;
    private static final long DISK_MAX_BYTES = 64L * 1024 * 1024;
    private static final String INSTANCE = "vault";
    private static final String NODE_ID = "node-1";
    private static final String ARTIFACTS = "artifacts";
    /// #858 C2 test seam bound -- far below the 30s production `DHT_MARKER_TIMEOUT` so the
    /// never-responding-client test proves the timeout cause in milliseconds. Mirrors
    /// `MavenProtocolRoutesTimeoutTest`'s injected `SHORT_TIMEOUT`.
    private static final TimeSpan SHORT_MARKER_TIMEOUT = timeSpan(150).millis();

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

    /// #874: `put` is now gated on the same `readGate` as `get` (previously ungated) -- a raw
    /// `writeThrough` against a DHT-backed instance must first resolve the marker check the way
    /// `AetherNode.start()` does post-formation, or `admission()` blocks it for the full 30s
    /// `admissionTimeout` and fails with `StorageError.TierNotAdmitted`. Mirrors the
    /// `dhtMarkerCheck()`/`verifyDhtMarker` sequence the read-side tests below already use
    /// (`verifyDhtMarker_fails_...`, `createAll_stillRefusesLegacyPlaintextBlock_...`). A no-op for
    /// instances with no DHT tier (`dhtMarkerCheck()` empty).
    private static void admitDhtMarker(StorageFactory.StorageSetup setup, DHTClient dhtClient) {
        setup.dhtMarkerCheck()
             .onPresent(check -> StorageFactory.verifyDhtMarker(dhtClient, check)
                                                .await()
                                                .onFailure(cause -> fail("verifying the DHT marker failed: " + cause.message())));
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

    private static Map<String, StorageFactory.StorageSetup> createAllOrFail(Map<String, StorageConfig> configs,
                                                                             Option<DHTClient> dhtClient,
                                                                             Option<EncryptionKeyring> keyring) {
        return StorageFactory.createAll(configs, NODE_ID, dhtClient, keyring)
                              .onFailure(cause -> fail("createAll must succeed: " + cause.message()))
                              .unwrap();
    }

    @Test
    void createAll_encryptsDiskTier_whenInstanceConfigEncryptedAndKeyringPresent() throws IOException {
        var diskDir = tempDir.resolve("vault-disk");
        var setups = createAllOrFail(Map.of(INSTANCE, storageConfigAt(diskDir, true)), Option.none(), Option.some(singleKeyRing("key-1")));

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
        var setups = createAllOrFail(Map.of(INSTANCE, storageConfigAt(diskDir, false)), Option.none(), Option.some(singleKeyRing("key-1")));

        assertThat(setups).containsKey(INSTANCE);

        var blockId = writeThrough(setups.get(INSTANCE));

        assertPlaintextAtRest(Files.readAllBytes(rawBlockPath(diskDir, blockId)), "the disk tier");
    }

    /// #253 ruling (2026-09-04): an operator who turns on `[storage.encryption]` must not have the
    /// auto-created default `artifacts` instance (no explicit `[storage.artifacts]` section) silently
    /// stay plaintext -- `defaultArtifactsConfig` now carries `encrypted = keyring.isPresent()`, the
    /// same outcome as an explicit `encrypted = true` section. This replaces the pre-ruling behaviour
    /// (see the plaintext-when-absent counterpart below), which is now the anti-regression pin for the
    /// OPPOSITE gate: mere keyring PRESENCE must still cover this instance.
    ///
    /// Deliberately asserted on the DHT tier rather than the disk tier: the synthesized default's
    /// `diskPath` is the fixed absolute `/data/aether/storage`, which is not creatable in a test
    /// sandbox, so `handleDiskTierUnavailable` degrades this instance to memory+DHT and there is no
    /// file to read. That degraded path is precisely where the coverage matters -- `maybeEncryptDht`
    /// applies the keyring on BOTH the disk-available and disk-unavailable branches, so an
    /// encryption gate removed from `createOne` (or from `defaultArtifactsConfig`) shows up here as
    /// plaintext in the DHT store regardless of whether the default disk path happens to be writable
    /// on the host.
    @Test
    void createAll_synthesizedDefaultArtifacts_isEncrypted_whenKeyringPresent() {
        var dhtClient = new InMemoryDHTClient();
        var setups = createAllOrFail(Map.of(), Option.some(dhtClient), Option.some(singleKeyRing("key-1")));

        assertThat(setups).containsKey(ARTIFACTS);

        admitDhtMarker(setups.get(ARTIFACTS), dhtClient);

        var blockId = writeThrough(setups.get(ARTIFACTS));
        var stored = dhtClient.rawValue("artifacts-blocks", blockId);

        assertThat(stored.isPresent()).as("the DHT tier is always present when a client is supplied, on both "
                                          + "the disk-available and the degraded memory+DHT path")
                                      .isTrue();
        stored.onPresent(raw -> assertCiphertextAtRest(raw, "the synthesized 'artifacts' DHT tier"));
    }

    /// The exact inverse of the test above, same shape as
    /// `createAll_leavesDiskTierPlaintext_whenInstanceConfigNotEncrypted_evenWithKeyringPresent`:
    /// with no keyring supplied at all, `defaultArtifactsConfig(false)` must still delegate to plain,
    /// unencrypted storage -- there is no keyring to gate on, so `createOne`'s effective keyring is
    /// empty regardless of the `encrypted` flag's value.
    @Test
    void createAll_synthesizedDefaultArtifacts_staysPlaintext_whenKeyringAbsent() {
        var dhtClient = new InMemoryDHTClient();
        var setups = createAllOrFail(Map.of(), Option.some(dhtClient), Option.none());

        assertThat(setups).containsKey(ARTIFACTS);

        admitDhtMarker(setups.get(ARTIFACTS), dhtClient);

        var blockId = writeThrough(setups.get(ARTIFACTS));
        var stored = dhtClient.rawValue("artifacts-blocks", blockId);

        assertThat(stored.isPresent()).as("the DHT tier is always present when a client is supplied, on both "
                                          + "the disk-available and the degraded memory+DHT path")
                                      .isTrue();
        stored.onPresent(raw -> assertPlaintextAtRest(raw, "the synthesized 'artifacts' DHT tier"));
    }

    /// #253 BLOCKING #1 (2026-09-04 ruling): replaces the pre-ruling `createAll_omitsInstance_...`
    /// test. `createAll` now returns `Result` and a per-instance construction failure -- enabling
    /// encryption over a directory that already holds unmarked plaintext, exactly like
    /// `EncryptingStorageTier#wrapLocalDisk`'s refusal -- aborts the WHOLE call rather than silently
    /// dropping just that one instance from the map and letting boot continue on whatever was left
    /// (the old behaviour, and BLOCKING #1's root cause paired with `AetherNode`'s now-removed
    /// `defaultArtifactStorage` fallback). `createOne` wraps the failure with the instance name via
    /// `mapError`, so the top-level cause names "vault" and its `source()` carries the original
    /// `EncryptionError.EnablingOverExistingPlaintext` unwrapped underneath.
    @Test
    void createAll_fails_whenDiskAlreadyHoldsPlaintextAndEncryptionRequested() {
        var diskDir = tempDir.resolve("vault-legacy-disk");

        seedRawPlaintextBlock(diskDir);

        var result = StorageFactory.createAll(Map.of(INSTANCE, storageConfigAt(diskDir, true)),
                                              NODE_ID,
                                              Option.none(),
                                              Option.some(singleKeyRing("key-1")));

        assertThat(result.isFailure()).as("an instance whose encryption enablement was refused must fail the "
                                          + "whole boot, not be silently dropped from the map")
                                      .isTrue();
        result.onFailure(cause -> {
            assertThat(cause.message()).as("the aggregate failure must name the failing instance")
                                       .contains(INSTANCE);
            assertThat(cause.source().isPresent()).as("the original refusal cause must be reachable underneath the "
                                                       + "instance-name wrapping")
                                                  .isTrue();
            assertThat(cause.source().unwrap()).isInstanceOf(EncryptionError.EnablingOverExistingPlaintext.class);
        });
    }

    /// #253 review round 3 SHOULD-FIX (2026-09-04 ruling), updated by #858: previously pinned an
    /// ordering fix where [#maybeEncryptDht]'s marker WRITE had to run only after the disk-side
    /// guard succeeded, never before it -- an eagerly-evaluated write could otherwise orphan a DHT
    /// marker on a boot the disk guard went on to refuse. #858 eliminates the hazard structurally
    /// rather than by reordering: `maybeEncryptDht` now performs no I/O at all -- it only builds an
    /// in-memory tier and a `DhtMarkerCheck` descriptor; the marker write itself happens exclusively
    /// in `StorageFactory.verifyDhtMarker`, run post-formation from `AetherNode.start()`, which this
    /// test (calling `createAll` directly) never reaches. This test now pins that invariant
    /// directly: `createAll` alone can never write (or block on) a DHT marker under any ordering,
    /// disk-guard outcome included.
    @Test
    void createAll_leavesNoDhtMarker_whenDiskGuardRefusesBeforeDhtEncryptionIsApplied() {
        var diskDir = tempDir.resolve("vault-legacy-disk-with-dht");
        var artifactsDir = tempDir.resolve("artifacts-disk");

        seedRawPlaintextBlock(diskDir);

        // Explicit, plain 'artifacts' entry: without it, `createAll` auto-synthesizes one that
        // shares this same `dhtClient` and independently succeeds/fails on the keyring's presence,
        // writing (and later tripping over) its own DHT marker for reasons unrelated to the ordering
        // bug this test pins on 'vault'. An explicit entry here bypasses that synthesis path entirely
        // and keeps the assertions below scoped to 'vault' alone.
        var dhtClient = new InMemoryDHTClient();
        var firstBoot = StorageFactory.createAll(Map.of(INSTANCE, storageConfigAt(diskDir, true),
                                                        ARTIFACTS, storageConfigAt(artifactsDir, false)),
                                                 NODE_ID,
                                                 Option.some(dhtClient),
                                                 Option.some(singleKeyRing("key-1")));

        assertThat(firstBoot.isFailure()).as("the disk-side legacy-plaintext guard must still refuse the boot "
                                            + "when a DHT client is also present")
                                         .isTrue();
        firstBoot.onFailure(cause -> assertThat(cause.source().unwrap()).isInstanceOf(EncryptionError.EnablingOverExistingPlaintext.class));

        var markerKey = (INSTANCE + "-blocks/" + EncryptingStorageTier.MARKER_FILE_NAME).getBytes(StandardCharsets.UTF_8);

        dhtClient.get(markerKey)
                 .await()
                 .onFailure(cause -> fail("reading the DHT marker key must not itself fail: " + cause.message()))
                 .onSuccess(marker -> assertThat(marker.isPresent()).as("a boot refused by the disk-side guard must "
                                                                        + "not have written the DHT marker first -- "
                                                                        + "the ordering bug this test pins would have "
                                                                        + "stamped the namespace as encrypted with no "
                                                                        + "ciphertext ever written under it")
                                                                    .isFalse());

        var secondBoot = StorageFactory.createAll(Map.of(INSTANCE, storageConfigAt(diskDir, false),
                                                         ARTIFACTS, storageConfigAt(artifactsDir, false)),
                                                  NODE_ID,
                                                  Option.some(dhtClient),
                                                  Option.none());

        assertThat(secondBoot.isSuccess()).as("#858: createAll never checks the DHT marker at construction time -- "
                                             + "rebooting the same DHT namespace must succeed regardless of any "
                                             + "marker; the check now runs later, post-formation, in "
                                             + "AetherNode.start()")
                                          .isTrue();
    }

    /// #253 BLOCKING #3 (2026-09-04 ruling): the reverse direction of the test above, through
    /// `StorageFactory` with real config rather than `EncryptingStorageTier` in isolation. Seeds the
    /// marker the way a real encrypted boot would (enable encryption, write a block through it), then
    /// reboots the SAME directory with `encrypted = false` -- the gap `buildTierList`'s no-keyring
    /// branch used to have: it returned the bare, unwrapped disk tier unconditionally, silently
    /// handing back framed `AEC1...` ciphertext as if it were the instance's plaintext content on
    /// every subsequent read. `createOne` wraps
    /// `EncryptingStorageTier#refuseIfEncryptedWithoutKeyring`'s refusal the same way it wraps
    /// `wrapLocalDisk`'s, so this failure also names the instance with the original
    /// `EncryptionError.EncryptedTierRequiresKeyring` reachable underneath.
    @Test
    void createAll_fails_whenDiskCarriesEncryptionMarker_andNoKeyringSuppliedForInstance() {
        var diskDir = tempDir.resolve("vault-was-encrypted");
        var seeded = createAllOrFail(Map.of(INSTANCE, storageConfigAt(diskDir, true)), Option.none(), Option.some(singleKeyRing("key-1")));

        writeThrough(seeded.get(INSTANCE));

        var result = StorageFactory.createAll(Map.of(INSTANCE, storageConfigAt(diskDir, false)), NODE_ID, Option.none(), Option.none());

        assertThat(result.isFailure()).as("booting a previously-encrypted disk directory with no keyring for this "
                                          + "instance must fail closed, not silently return the bare tier over "
                                          + "existing ciphertext")
                                      .isTrue();
        result.onFailure(cause -> {
            assertThat(cause.message()).contains(INSTANCE);
            assertThat(cause.source().isPresent()).isTrue();
            assertThat(cause.source().unwrap()).isInstanceOf(EncryptionError.EncryptedTierRequiresKeyring.class);
        });
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

    /// #253 BLOCKING #3 extension (2026-09-04, beyond the two call sites the review cited -- see
    /// `StorageFactory.defaultStreamStorage`'s Javadoc): the streams segments directory has the
    /// identical reverse-direction gap as the per-instance disk path above. Unlike `createAll`, the
    /// refusal here is NOT wrapped with instance-name context (`defaultStreamStorage`'s no-keyring
    /// branch propagates `refuseIfEncryptedWithoutKeyring`'s `Result` directly), so the cause IS the
    /// `EncryptionError.EncryptedTierRequiresKeyring` itself, not a wrapper around it.
    @Test
    void defaultStreamStorage_fails_whenSegmentsDirCarriesEncryptionMarker_andNoKeyringSupplied() {
        var streamDataDir = tempDir.resolve("streams-was-encrypted");
        var seeded = StorageFactory.defaultStreamStorage(Option.none(), streamDataDir, NODE_ID, Option.some(singleKeyRing("key-1")))
                                    .onFailure(cause -> fail("seeding the encrypted streams marker failed: " + cause.message()))
                                    .unwrap();

        writeThrough(seeded);

        StorageFactory.defaultStreamStorage(Option.none(), streamDataDir, NODE_ID, Option.none())
                      .onSuccess(_ -> fail("booting streams with a marker present and no keyring must fail closed"))
                      .onFailure(cause -> assertThat(cause).isInstanceOf(EncryptionError.EncryptedTierRequiresKeyring.class));
    }

    /// The design calls for a decorator over the LocalDisk AND DHT tiers; tests 1-2 only cover disk.
    /// This pins the DHT half: the block's durable copy (the DHT tier is last, hence the durable
    /// tier, when a client is present) must be ciphertext in the backing store.
    @Test
    void createAll_encryptsDhtTier_whenInstanceConfigEncryptedAndKeyringPresent() {
        var dhtClient = new InMemoryDHTClient();
        var setups = createAllOrFail(Map.of(INSTANCE, storageConfigAt(tempDir.resolve("vault-dht-disk"), true)),
                                     Option.some(dhtClient),
                                     Option.some(singleKeyRing("key-1")));

        assertThat(setups).containsKey(INSTANCE);

        admitDhtMarker(setups.get(INSTANCE), dhtClient);

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
        var setups = createAllOrFail(Map.of(INSTANCE, storageConfigAt(tempDir.resolve("vault-dht-plain"), false)),
                                     Option.some(dhtClient),
                                     Option.some(singleKeyRing("key-1")));

        assertThat(setups).containsKey(INSTANCE);

        admitDhtMarker(setups.get(INSTANCE), dhtClient);

        var blockId = writeThrough(setups.get(INSTANCE));
        var stored = dhtClient.rawValue(INSTANCE + "-blocks", blockId);

        assertThat(stored.isPresent()).isTrue();
        stored.onPresent(raw -> assertPlaintextAtRest(raw, "the DHT tier"));
    }

    /// #253 SHOULD-FIX #1, superseded by #858 (2026-09-04 ruling): the boot-time refusal this test
    /// used to pin now runs post-formation instead -- [StorageFactory#maybeEncryptDht] performs no
    /// I/O, so `createAll` always succeeds and hands back a `dhtMarkerCheck` for the instance rather
    /// than checking the marker itself. This test now pins the refusal one layer down, calling
    /// `StorageFactory.verifyDhtMarker` directly against that check -- exactly what
    /// `AetherNode.start()` does post-formation -- on the same degraded (disk-unavailable) path
    /// review round 2 named.
    @Test
    void verifyDhtMarker_fails_whenDhtCarriesEncryptionMarker_andDiskUnavailable_andNoKeyringSupplied() throws IOException {
        var brokenDiskPath = tempDir.resolve("vault-disk-unavailable");

        Files.writeString(brokenDiskPath, "a plain file here forces LocalDiskTier construction to fail");

        var dhtClient = new InMemoryDHTClient();
        var seeded = createAllOrFail(Map.of(INSTANCE, storageConfigAt(brokenDiskPath, true)),
                                     Option.some(dhtClient),
                                     Option.some(singleKeyRing("key-1")));

        // #858: `createAll` performs no DHT I/O any more -- the marker is written only when
        // `StorageFactory.verifyDhtMarker` runs post-formation, exactly as `AetherNode.start()`
        // does. Without this call the reboot below would find no marker at all (absent -> success),
        // never reaching the keyring-mismatch this test pins.
        seeded.get(INSTANCE)
              .dhtMarkerCheck()
              .onPresent(check -> StorageFactory.verifyDhtMarker(dhtClient, check)
                                                 .await()
                                                 .onFailure(cause -> fail("writing the DHT marker on first boot failed: " + cause.message())));

        writeThrough(seeded.get(INSTANCE));

        var reboot = createAllOrFail(Map.of(INSTANCE, storageConfigAt(brokenDiskPath, false)),
                                     Option.some(dhtClient),
                                     Option.none());

        var check = reboot.get(INSTANCE).dhtMarkerCheck();

        assertThat(check.isPresent()).as("an instance with a DHT tier must always carry a marker check for "
                                         + "AetherNode.start() to verify post-formation")
                                     .isTrue();

        check.onPresent(c -> StorageFactory.verifyDhtMarker(dhtClient, c)
                                           .await()
                                           .onSuccess(_ -> fail("booting a previously-encrypted DHT namespace with the "
                                                                + "disk tier unavailable and no keyring for this instance "
                                                                + "must fail closed, not silently resolve the read gate "
                                                                + "over existing ciphertext"))
                                           .onFailure(cause -> assertThat(cause).isInstanceOf(EncryptionError.EncryptedTierRequiresKeyring.class)));
    }

    /// #875: the test above proves `verifyDhtMarker`'s RETURNED `Promise` fails with the refusal
    /// cause -- it says nothing about `check.readGate()` itself, the shared promise
    /// [org.pragmatica.aether.storage.DhtStorageTier] actually blocks on. `DhtStorageTierTest`'s
    /// admission-race coverage constructs and resolves `readGate` BY HAND
    /// (`readGate.resolve(Result.failure(refusal))`), so it pins `DhtStorageTier`'s reaction to an
    /// already-resolved gate but cannot prove `verifyDhtMarker` is what resolves it in production --
    /// that pins its own fixture, not the production path. This test drives the real
    /// no-keyring-refusal branch end to end (same setup as the test above) and reads `readGate()`
    /// directly afterward; nothing here calls `.resolve(...)`. Before #875, the failure branch of
    /// `verifyDhtMarker` left `readGate` unresolved, so a caller racing this check (a read/write
    /// arriving mid-formation) would wait out the full `admissionTimeout` and see
    /// `StorageError.TierNotAdmitted` instead of the real cause asserted here.
    @Test
    void verifyDhtMarker_resolvesReadGate_withRefusalCause_onProductionFailurePath() throws IOException {
        var brokenDiskPath = tempDir.resolve("vault-disk-unavailable-gate");

        Files.writeString(brokenDiskPath, "a plain file here forces LocalDiskTier construction to fail");

        var dhtClient = new InMemoryDHTClient();
        var seeded = createAllOrFail(Map.of(INSTANCE, storageConfigAt(brokenDiskPath, true)),
                                     Option.some(dhtClient),
                                     Option.some(singleKeyRing("key-1")));

        seeded.get(INSTANCE)
              .dhtMarkerCheck()
              .onPresent(check -> StorageFactory.verifyDhtMarker(dhtClient, check)
                                                 .await()
                                                 .onFailure(cause -> fail("writing the DHT marker on first boot failed: " + cause.message())));

        var reboot = createAllOrFail(Map.of(INSTANCE, storageConfigAt(brokenDiskPath, false)),
                                     Option.some(dhtClient),
                                     Option.none());

        var check = reboot.get(INSTANCE).dhtMarkerCheck();

        assertThat(check.isPresent()).as("an instance with a DHT tier must always carry a marker check for "
                                         + "AetherNode.start() to verify post-formation")
                                     .isTrue();

        check.onPresent(c -> {
            StorageFactory.verifyDhtMarker(dhtClient, c)
                          .await()
                          .onSuccess(_ -> fail("booting a previously-encrypted DHT namespace with no keyring "
                                              + "must fail closed"))
                          .onFailure(cause -> assertThat(cause).isInstanceOf(EncryptionError.EncryptedTierRequiresKeyring.class));

            // #875: the gate itself, not merely the returned Promise, must carry the refusal. If
            // `verifyDhtMarker`'s failure branch ever dropped `check.readGate().resolve(...)`, this
            // `.await()` would hang until DhtStorageTier's own admission bound (30s in production)
            // rather than complete here.
            c.readGate()
             .await()
             .onSuccess(_ -> fail("#875: a refused marker check must resolve readGate to FAILURE with the "
                                  + "refusal cause, not success -- a caller racing this check must fail "
                                  + "immediately, not be admitted"))
             .onFailure(cause -> assertThat(cause).as("#875: readGate must carry the SAME refusal cause the "
                                                       + "returned Promise failed with, not a generic or "
                                                       + "unresolved state")
                                                   .isInstanceOf(EncryptionError.EncryptedTierRequiresKeyring.class));
        });
    }

    /// #858 C2: two distinct causes must never be conflated. Marker get/put timing out after
    /// formation (this test: the DHT client itself never answers) means `start()` never learned
    /// whether a marker exists at all -- fails on `EncryptionError.DhtMarkerCheckTimedOut`. That is
    /// the opposite situation from `EncryptedTierRequiresKeyring` (the marker WAS read successfully
    /// and named a key id absent from the keyring): a never-resolving DHT client must never be
    /// misreported as that cause. Uses the package-private `verifyDhtMarker(..., timeout)` test seam
    /// (mirrors `MavenProtocolRoutesTimeoutTest`'s injected `SHORT_TIMEOUT`) so this proves the bound
    /// in milliseconds rather than waiting out the real 30s `DHT_MARKER_TIMEOUT`.
    @Test
    void verifyDhtMarker_fails_withDhtMarkerCheckTimedOut_whenDhtClientNeverResponds() {
        var neverRespondingClient = new NeverRespondingDHTClient();
        var seeded = createAllOrFail(Map.of(INSTANCE, storageConfigAt(tempDir.resolve("vault-hang"), false)),
                                     Option.some(neverRespondingClient),
                                     Option.none());

        var check = seeded.get(INSTANCE).dhtMarkerCheck();

        assertThat(check.isPresent()).as("an instance with a DHT tier must always carry a marker check for "
                                         + "AetherNode.start() to verify post-formation")
                                     .isTrue();

        check.onPresent(c -> StorageFactory.verifyDhtMarker(neverRespondingClient, c, SHORT_MARKER_TIMEOUT)
                                           .await()
                                           .onSuccess(_ -> fail("a marker check whose DHT round trip never resolves must "
                                                                + "not succeed"))
                                           .onFailure(cause -> assertThat(cause).isInstanceOf(EncryptionError.DhtMarkerCheckTimedOut.class)));
    }

    /// #253 SHOULD-FIX #1 (2026-09-04 ruling): the legacy/forward-direction counterpart to the test
    /// above, on the same degraded (disk-unavailable) path -- confirms the new DHT marker mechanism
    /// leaves the EXISTING, unchanged per-block legacy-plaintext detection
    /// ([org.pragmatica.storage.EncryptingStorageTier#get]) untouched. There is still no boot-time
    /// FORWARD scan for a DHT tier (unlike [org.pragmatica.storage.EncryptingStorageTier#wrapLocalDisk]'s
    /// directory walk) -- a raw plaintext block written before encryption was ever enabled for this
    /// namespace is invisible to the marker write/check added above, and stays detectable only
    /// reactively, per read, exactly as before this round.
    @Test
    void createAll_stillRefusesLegacyPlaintextBlock_perRead_onDhtTier_whenDiskUnavailable() throws IOException {
        var brokenDiskPath = tempDir.resolve("vault-disk-unavailable-legacy");

        Files.writeString(brokenDiskPath, "a plain file here forces LocalDiskTier construction to fail");

        var dhtClient = new InMemoryDHTClient();
        var legacyBlockId = BlockId.blockId(PLAINTEXT).unwrap();

        // Seeds a RAW plaintext block directly into the DHT store, exactly as a pre-#253 unencrypted
        // boot would have left it -- bypassing the tier entirely, mirroring `seedRawPlaintextBlock`'s
        // disk-side technique. No marker exists yet, so this is the "empty/fresh" case as far as the
        // new marker check is concerned.
        dhtClient.put(INSTANCE + "-blocks/" + legacyBlockId.hexString(), PLAINTEXT)
                 .await()
                 .onFailure(cause -> fail("seeding a raw plaintext DHT block failed: " + cause.message()));

        var seeded = createAllOrFail(Map.of(INSTANCE, storageConfigAt(brokenDiskPath, true)),
                                     Option.some(dhtClient),
                                     Option.some(singleKeyRing("key-1")));

        // #858: `DhtStorageTier.get()` is now gated on a per-instance readGate that only
        // `StorageFactory.verifyDhtMarker` resolves -- mirroring the post-formation step
        // `AetherNode.start()` runs before the node reports ready. An unresolved gate would hang
        // the read below forever (`.await()` has no timeout), so verify the marker first, exactly
        // as production does.
        seeded.get(INSTANCE)
              .dhtMarkerCheck()
              .onPresent(check -> StorageFactory.verifyDhtMarker(dhtClient, check)
                                                 .await()
                                                 .onFailure(cause -> fail("verifying the DHT marker failed: " + cause.message())));

        seeded.get(INSTANCE)
              .instance()
              .get(legacyBlockId)
              .await()
              .onSuccess(_ -> fail("a raw plaintext block predating encryption must not decrypt or pass through "
                                   + "silently just because a later boot enabled encryption over this DHT namespace"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(EncryptionError.LegacyPlaintextBlock.class));
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

    /// #858 C2: a `DHTClient` whose round trip never resolves, so `verifyDhtMarker`'s `.timeout(...)`
    /// is the only thing that ever completes the call -- proving the timeout cause fires on a hung
    /// client rather than only on an explicit test-injected failure. Mirrors
    /// `MavenProtocolRoutesTimeoutTest`'s `neverResolvingHandler()` idiom (`Promise.promise()`, never
    /// resolved).
    private static final class NeverRespondingDHTClient implements DHTClient {
        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            return Promise.promise(); // never resolves
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            return Promise.promise(); // never resolves
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            return Promise.promise(); // never invoked on this path; kept unresolved for consistency
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return Promise.promise(); // never invoked on this path; kept unresolved for consistency
        }

        @Override
        public Partition partitionFor(byte[] key) {
            return null;
        }
    }
}
