package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #253: [EncryptingStorageTier] built through its own production factories (`wrap`/`wrapLocalDisk`)
/// wrapping a real [LocalDiskTier] -- never a hand-rolled stand-in -- so these tests exercise the
/// exact object StorageFactory will construct in production.
class EncryptingStorageTierTest {

    private static final byte[] CONTENT = "plaintext-block-content-253".getBytes(StandardCharsets.UTF_8);
    private static final long MAX_BYTES = 10 * 1024 * 1024;

    @TempDir
    Path tempDir;

    private static byte[] randomKey() {
        var key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static BlockEncryptor encryptorOf(String keyId) {
        return BlockEncryptor.aesGcm(randomKey(), keyId)
                              .fold(c -> { fail("encryptor creation failed: " + c.message()); return null; },
                                    e -> e);
    }

    private static BlockId blockIdOf(byte[] content) {
        return BlockId.blockId(content)
                      .fold(_ -> { fail("BlockId creation failed"); return null; },
                            id -> id);
    }

    private static EncryptionKeyring singleKeyRing(String keyId) {
        return EncryptionKeyring.encryptionKeyring(Map.of(keyId, encryptorOf(keyId)), keyId)
                                 .fold(c -> { fail("keyring creation failed: " + c.message()); return null; },
                                       k -> k);
    }

    /// Two ring entries under DIFFERENT key ids but the SAME underlying secret key. This is what
    /// makes [HeaderAuthentication#tamperedKeyId_swappedToAnotherRingKey_sharingTheSameSecret_stillFailsAuthentication]
    /// a valid pin on AAD-over-the-header (rather than passing vacuously): with distinct secrets, a
    /// key-id swap would fail GCM authentication for the mundane reason that the wrong AES key was
    /// used, which proves nothing about whether the key id itself is authenticated data. With a
    /// shared secret, decryption uses the CORRECT key either way, so a failure can only come from
    /// the AAD (which embeds the key id) no longer matching what was bound at encryption time.
    private static EncryptionKeyring twoKeyRingSharedSecret(String activeId, String otherId) {
        var sharedKey = randomKey();
        var active = BlockEncryptor.aesGcm(sharedKey, activeId)
                                    .fold(c -> { fail("encryptor creation failed: " + c.message()); return null; }, e -> e);
        var other = BlockEncryptor.aesGcm(sharedKey, otherId)
                                   .fold(c -> { fail("encryptor creation failed: " + c.message()); return null; }, e -> e);

        return EncryptionKeyring.encryptionKeyring(Map.of(activeId, active, otherId, other), activeId)
                                 .fold(c -> { fail("keyring creation failed: " + c.message()); return null; },
                                       k -> k);
    }

    private LocalDiskTier freshDiskTier() {
        return LocalDiskTier.localDiskTier(tempDir, MAX_BYTES)
                             .fold(c -> { fail("disk tier creation failed: " + c.message()); return null; },
                                   t -> t);
    }

    /// Mirrors LocalDiskTier's own private `blockPath` layout exactly, so tests can inspect what
    /// actually landed on disk without going through the (encrypting) tier's own `get`.
    private Path rawBlockPath(BlockId id) {
        var hex = id.hexString();

        return tempDir.resolve(hex.substring(0, 2))
                      .resolve(hex.substring(2, 4))
                      .resolve(hex);
    }

    @Nested
    class LocalDiskRoundTrip {

        @Test
        void put_get_roundTrip_returnsOriginalPlaintext() {
            var tier = EncryptingStorageTier.wrapLocalDisk(freshDiskTier(), tempDir, singleKeyRing("key-1"))
                                             .fold(c -> { fail("wrap failed: " + c.message()); return null; }, t -> t);
            var id = blockIdOf(CONTENT);

            tier.put(id, CONTENT).await()
                .onFailure(c -> fail("put failed: " + c.message()));

            tier.get(id).await()
                .onFailure(c -> fail("get failed: " + c.message()))
                .onSuccess(opt -> {
                    assertThat(opt.isPresent()).isTrue();
                    opt.onPresent(data -> assertThat(data).isEqualTo(CONTENT));
                });
        }

        @Test
        void put_storesFramedCiphertext_notRawPlaintext_onDisk() throws Exception {
            var tier = EncryptingStorageTier.wrapLocalDisk(freshDiskTier(), tempDir, singleKeyRing("key-1"))
                                             .fold(c -> { fail("wrap failed: " + c.message()); return null; }, t -> t);
            var id = blockIdOf(CONTENT);

            tier.put(id, CONTENT).await()
                .onFailure(c -> fail("put failed: " + c.message()));

            var raw = Files.readAllBytes(rawBlockPath(id));

            assertThat(java.util.Arrays.copyOfRange(raw, 0, EncryptingStorageTier.MAGIC.length))
                    .isEqualTo(EncryptingStorageTier.MAGIC);
            assertThat(new String(raw, StandardCharsets.UTF_8)).doesNotContain(new String(CONTENT, StandardCharsets.UTF_8));
        }
    }

    @Nested
    class LegacyPlaintextDetection {

        @Test
        void get_blockWithNoHeader_fails_withLegacyPlaintextBlock_namingBlockId() {
            var disk = freshDiskTier();
            var id = blockIdOf(CONTENT);

            // written through the RAW (unwrapped) disk tier -- simulates a block that predates
            // encryption being enabled on this instance.
            disk.put(id, CONTENT).await()
                .onFailure(c -> fail("raw put failed: " + c.message()));

            var tier = EncryptingStorageTier.wrap(disk, singleKeyRing("key-1"));

            tier.get(id).await()
                .onSuccess(_ -> fail("read of a headerless block must fail, not return raw bytes"))
                .onFailure(cause -> {
                    assertThat(cause).isInstanceOf(EncryptionError.LegacyPlaintextBlock.class);
                    assertThat(cause.message()).contains(id.hexString());
                });
        }
    }

    @Nested
    class BootTimeLegacyGuard {

        @Test
        void wrapLocalDisk_refusesBoot_whenDirectoryHoldsPlaintextBlocks_noMarker() {
            var disk = freshDiskTier();
            var id = blockIdOf(CONTENT);

            disk.put(id, CONTENT).await()
                .onFailure(c -> fail("raw put failed: " + c.message()));

            EncryptingStorageTier.wrapLocalDisk(disk, tempDir, singleKeyRing("key-1"))
                                  .onSuccess(_ -> fail("boot must refuse enabling encryption over existing plaintext"))
                                  .onFailure(cause -> {
                                      assertThat(cause).isInstanceOf(EncryptionError.EnablingOverExistingPlaintext.class);
                                      assertThat(cause.message()).contains(tempDir.toString());
                                  });
        }

        @Test
        void wrapLocalDisk_succeeds_onFreshEmptyDirectory_andWritesMarker() throws Exception {
            EncryptingStorageTier.wrapLocalDisk(freshDiskTier(), tempDir, singleKeyRing("key-1"))
                                  .onFailure(c -> fail("wrap on an empty directory must succeed: " + c.message()));

            var markerPath = tempDir.resolve(EncryptingStorageTier.MARKER_FILE_NAME);

            assertThat(Files.exists(markerPath)).isTrue();
            // #253 BLOCKING #3: the marker persists the ACTIVE KEY ID, not empty bytes -- this is what
            // lets a later reverse-direction refusal name which key the operator needs, instead of the
            // "<unknown -- marker predates key-id persistence>" fallback.
            assertThat(new String(Files.readAllBytes(markerPath), StandardCharsets.UTF_8)).isEqualTo("key-1");
        }

        @Test
        void wrapLocalDisk_succeeds_whenMarkerAlreadyPresent_regardlessOfPlaintextBlocks() throws Exception {
            var disk = freshDiskTier();
            var id = blockIdOf(CONTENT);

            disk.put(id, CONTENT).await()
                .onFailure(c -> fail("raw put failed: " + c.message()));
            Files.createFile(tempDir.resolve(EncryptingStorageTier.MARKER_FILE_NAME));

            EncryptingStorageTier.wrapLocalDisk(disk, tempDir, singleKeyRing("key-1"))
                                  .onFailure(c -> fail("wrap with the marker already present must succeed: " + c.message()));
        }
    }

    /// #253 BLOCKING #3 (2026-09-04 ruling): direct unit coverage of
    /// [EncryptingStorageTier#refuseIfEncryptedWithoutKeyring] -- the reverse-direction guard, called
    /// from the no-keyring branch of tier assembly. `StorageFactoryEncryptionTest` (`aether/node`)
    /// covers the same guard wired through real `StorageFactory`/`AetherConfig` boot paths; these pin
    /// the primitive itself, including the pre-key-id-persistence fallback string, at the unit level.
    @Nested
    class ReverseDirectionGuard {

        @Test
        void refuseIfEncryptedWithoutKeyring_succeeds_whenNoMarkerPresent() {
            EncryptingStorageTier.refuseIfEncryptedWithoutKeyring(tempDir, "some-instance")
                                  .onFailure(c -> fail("a directory with no marker must not be refused: " + c.message()));
        }

        @Test
        void refuseIfEncryptedWithoutKeyring_fails_namingInstanceAndActiveKeyId_whenMarkerPresent() {
            EncryptingStorageTier.wrapLocalDisk(freshDiskTier(), tempDir, singleKeyRing("key-1"))
                                  .onFailure(c -> fail("seeding the marker failed: " + c.message()));

            EncryptingStorageTier.refuseIfEncryptedWithoutKeyring(tempDir, "vault")
                                  .onSuccessRun(() -> fail("a directory that was previously encrypted must refuse a plain reboot"))
                                  .onFailure(cause -> {
                                      assertThat(cause).isInstanceOf(EncryptionError.EncryptedTierRequiresKeyring.class);
                                      var typed = (EncryptionError.EncryptedTierRequiresKeyring) cause;
                                      assertThat(typed.instanceName()).isEqualTo("vault");
                                      assertThat(typed.keyId()).isEqualTo("key-1");
                                  });
        }

        @Test
        void refuseIfEncryptedWithoutKeyring_fails_withUnknownKeyId_whenMarkerPredatesKeyIdPersistence() throws Exception {
            // an empty marker file, as `writeMarker` would have produced before it started persisting
            // the active key id -- the fallback string this asserts is the only way an operator with an
            // old marker on disk learns anything at all from this refusal.
            Files.createFile(tempDir.resolve(EncryptingStorageTier.MARKER_FILE_NAME));

            EncryptingStorageTier.refuseIfEncryptedWithoutKeyring(tempDir, "vault")
                                  .onSuccessRun(() -> fail("a directory carrying even an empty marker must refuse a plain reboot"))
                                  .onFailure(cause -> assertThat(((EncryptionError.EncryptedTierRequiresKeyring) cause).keyId())
                                                     .isEqualTo("<unknown -- marker predates key-id persistence>"));
        }
    }

    @Nested
    class HeaderAuthentication {

        @Test
        void tamperedKeyId_swappedToAnotherRingKey_sharingTheSameSecret_stillFailsAuthentication() throws Exception {
            // "key-A"/"key-B" share the SAME underlying AES key (see twoKeyRingSharedSecret) and have
            // the same UTF-8 byte length, so the swap preserves header framing, both ids resolve in
            // the ring, AND decryption uses the objectively correct key either way -- isolating the
            // AAD-over-the-header guarantee from an unrelated "wrong key fails GCM" confound.
            var tier = EncryptingStorageTier.wrapLocalDisk(freshDiskTier(), tempDir, twoKeyRingSharedSecret("key-A", "key-B"))
                                             .fold(c -> { fail("wrap failed: " + c.message()); return null; }, t -> t);
            var id = blockIdOf(CONTENT);

            tier.put(id, CONTENT).await()
                .onFailure(c -> fail("put failed: " + c.message()));

            var path = rawBlockPath(id);
            Files.write(path, swapKeyId(Files.readAllBytes(path), "key-B"));

            tier.get(id).await()
                .onSuccess(_ -> fail("a key-id swapped to a DIFFERENT ring key -- even one sharing the same secret -- "
                                     + "must fail: the key id is authenticated data, not merely a lookup token"))
                .onFailure(cause -> assertThat(cause).isInstanceOf(EncryptionError.DecryptionFailed.class));
        }

        /// #253 SHOULD-FIX #4: the block's own id is folded into the AAD (see class Javadoc), so
        /// moving one block's stored bytes onto a DIFFERENT block's on-disk path -- both encrypted
        /// under the identical key, with an otherwise byte-identical header -- must not decrypt as
        /// the target block's plaintext. Header-only AAD (magic+version+keyId) would authenticate
        /// this swap silently; block-id AAD does not. Distinct from the key-id-swap test above: this
        /// attack never touches the header bytes at all, only the file each set of bytes is read
        /// back through.
        @Test
        void blockSwap_ciphertextMovedToAnotherBlocksOnDiskPath_failsAuthentication() throws Exception {
            var keyring = singleKeyRing("key-1");
            var tier = EncryptingStorageTier.wrapLocalDisk(freshDiskTier(), tempDir, keyring)
                                             .fold(c -> { fail("wrap failed: " + c.message()); return null; }, t -> t);
            var contentA = "block-A-distinct-content-253".getBytes(StandardCharsets.UTF_8);
            var contentB = "block-B-distinct-content-253".getBytes(StandardCharsets.UTF_8);
            var idA = blockIdOf(contentA);
            var idB = blockIdOf(contentB);

            tier.put(idA, contentA).await()
                .onFailure(c -> fail("put A failed: " + c.message()));

            var framedA = Files.readAllBytes(rawBlockPath(idA));
            var pathB = rawBlockPath(idB);

            Files.createDirectories(pathB.getParent());
            Files.write(pathB, framedA);

            tier.get(idB).await()
                .onSuccess(_ -> fail("ciphertext relocated from block A's on-disk path to block B's must not "
                                     + "decrypt as block B's plaintext -- the block id is authenticated data, "
                                     + "not merely a lookup key"))
                .onFailure(cause -> assertThat(cause).isInstanceOf(EncryptionError.DecryptionFailed.class));
        }

        @Test
        void unknownKeyId_inHeader_fails_namingTheMissingKeyId() {
            var disk = freshDiskTier();
            var writingTier = EncryptingStorageTier.wrapLocalDisk(disk, tempDir, singleKeyRing("key-1"))
                                                    .fold(c -> { fail("wrap failed: " + c.message()); return null; }, t -> t);
            var id = blockIdOf(CONTENT);

            writingTier.put(id, CONTENT).await()
                       .onFailure(c -> fail("put failed: " + c.message()));

            // a ring that no longer carries "key-1" -- as if the key had been retired/removed.
            var readingTier = EncryptingStorageTier.wrap(disk, singleKeyRing("key-2"));

            readingTier.get(id).await()
                       .onSuccess(_ -> fail("read against a ring missing the block's key id must fail"))
                       .onFailure(cause -> {
                           assertThat(cause).isInstanceOf(EncryptionError.UnknownKeyId.class);
                           assertThat(cause.message()).contains("key-1");
                       });
        }

        /// Overwrites the key-id field of a framed block in place. `toId` MUST be the same UTF-8
        /// byte length as the original id, or the swap corrupts the nonce/ciphertext offsets too.
        private static byte[] swapKeyId(byte[] framed, String toId) {
            var toBytes = toId.getBytes(StandardCharsets.UTF_8);
            var out = framed.clone();

            System.arraycopy(toBytes, 0, out, EncryptingStorageTier.HEADER_FIXED_LEN, toBytes.length);

            return out;
        }
    }

    /// #253 SHOULD-FIX #5 and the NOTE item: [EncryptingStorageTier#parseHeader]'s three failure
    /// classifications on a malformed or unsupported wire format, pinned directly against
    /// hand-assembled framed bytes rather than through a legitimate `put`.
    @Nested
    class WireFormatValidation {

        @Test
        void get_blockWithMismatchedVersionByte_fails_withUnsupportedVersion_namingBothVersions() throws Exception {
            var tier = EncryptingStorageTier.wrapLocalDisk(freshDiskTier(), tempDir, singleKeyRing("key-1"))
                                             .fold(c -> { fail("wrap failed: " + c.message()); return null; }, t -> t);
            var id = blockIdOf(CONTENT);

            tier.put(id, CONTENT).await()
                .onFailure(c -> fail("put failed: " + c.message()));

            var path = rawBlockPath(id);
            var framed = Files.readAllBytes(path);
            var bumpedVersion = (byte) (EncryptingStorageTier.VERSION + 1);

            framed[EncryptingStorageTier.MAGIC.length] = bumpedVersion;
            Files.write(path, framed);

            tier.get(id).await()
                .onSuccess(_ -> fail("a version byte that doesn't match the reader's supported version must fail "
                                     + "closed, not be silently (mis)decrypted as the current format"))
                .onFailure(cause -> {
                    assertThat(cause).isInstanceOf(EncryptionError.UnsupportedVersion.class);

                    var unsupported = (EncryptionError.UnsupportedVersion) cause;

                    assertThat(unsupported.blockId()).isEqualTo(id.hexString());
                    assertThat(unsupported.actualVersion()).isEqualTo(bumpedVersion & 0xFF);
                    assertThat(unsupported.expectedVersion()).isEqualTo(EncryptingStorageTier.VERSION & 0xFF);
                });
        }

        /// The classification boundary the NOTE item calls for: magic present but too short to carry
        /// even the fixed header is CORRUPTION ([EncryptionError.MalformedHeader]), never
        /// [EncryptionError.LegacyPlaintextBlock] -- that classification is reserved for a block with
        /// no magic at all (see [LegacyPlaintextDetection]).
        @Test
        void get_blockTruncatedRightAfterMagic_fails_withMalformedHeader_neverLegacyPlaintext() {
            var disk = freshDiskTier();
            var id = blockIdOf(CONTENT);
            // MAGIC plus one stray byte -- starts with MAGIC, but shorter than HEADER_FIXED_LEN, so it
            // cannot carry the version/key-id-length fields at all.
            var truncated = java.util.Arrays.copyOf(EncryptingStorageTier.MAGIC, EncryptingStorageTier.MAGIC.length + 1);

            disk.put(id, truncated).await()
                .onFailure(c -> fail("raw put failed: " + c.message()));

            var tier = EncryptingStorageTier.wrap(disk, singleKeyRing("key-1"));

            tier.get(id).await()
                .onSuccess(_ -> fail("a block starting with MAGIC but too short to carry the fixed header is "
                                     + "corruption, not a legacy plaintext write"))
                .onFailure(cause -> {
                    assertThat(cause).as("magic present but header incomplete must classify as MalformedHeader, "
                                        + "never as LegacyPlaintextBlock")
                                     .isInstanceOf(EncryptionError.MalformedHeader.class)
                                     .isNotInstanceOf(EncryptionError.LegacyPlaintextBlock.class);
                    assertThat(cause.message()).contains(id.hexString());
                });
        }

        @Test
        void get_blockTruncatedBeforeNonceAndCiphertext_fails_withMalformedHeader() {
            var disk = freshDiskTier();
            var id = blockIdOf(CONTENT);
            var keyIdBytes = "key-1".getBytes(StandardCharsets.UTF_8);
            // a syntactically valid fixed header plus key id, but NOTHING after it -- no nonce, no
            // ciphertext at all.
            var header = new byte[EncryptingStorageTier.HEADER_FIXED_LEN + keyIdBytes.length];

            System.arraycopy(EncryptingStorageTier.MAGIC, 0, header, 0, EncryptingStorageTier.MAGIC.length);
            header[EncryptingStorageTier.MAGIC.length] = EncryptingStorageTier.VERSION;
            header[EncryptingStorageTier.MAGIC.length + 1] = (byte) ((keyIdBytes.length >> 8) & 0xFF);
            header[EncryptingStorageTier.MAGIC.length + 2] = (byte) (keyIdBytes.length & 0xFF);
            System.arraycopy(keyIdBytes, 0, header, EncryptingStorageTier.HEADER_FIXED_LEN, keyIdBytes.length);

            disk.put(id, header).await()
                .onFailure(c -> fail("raw put failed: " + c.message()));

            var tier = EncryptingStorageTier.wrap(disk, singleKeyRing("key-1"));

            tier.get(id).await()
                .onSuccess(_ -> fail("a header naming a valid key id but carrying no nonce/ciphertext at all is corruption"))
                .onFailure(cause -> {
                    assertThat(cause).isInstanceOf(EncryptionError.MalformedHeader.class);
                    assertThat(cause.message()).contains(id.hexString());
                });
        }
    }
}
