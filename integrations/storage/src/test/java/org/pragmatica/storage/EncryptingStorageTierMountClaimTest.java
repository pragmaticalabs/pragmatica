package org.pragmatica.storage;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// A0b: the mount claim file is bookkeeping, not a block, to the encryption guard; and the encrypting wrapper hands the
/// claim back when the instance shuts down. Each test names its mutation.
class EncryptingStorageTierMountClaimTest {
    @TempDir
    Path mount;

    /// Mutation: `isBookkeeping` stops excluding [MountClaim#FILE_NAME] -- the guard reads the claim as plaintext blocks
    /// and refuses to arm encryption over a directory that holds none.
    @Test
    void armLocalDisk_overAClaimedEmptyDirectory_arms() {
        var disk = LocalDiskTier.localDiskTier(mount, 1_000_000L).unwrap().open("node-a").unwrap();

        assertThat(EncryptingStorageTier.armLocalDisk(disk, mount, keyring()).isSuccess())
            .as("the claim file is not a plaintext block")
            .isTrue();
        disk.release();
    }

    /// Mutation: `EncryptingStorageTier.release` does not delegate -- an encrypted instance keeps its claim after shutdown.
    @Test
    void release_throughTheEncryptingWrapper_givesTheClaimBack() {
        var disk = LocalDiskTier.localDiskTier(mount, 1_000_000L).unwrap().open("node-a").unwrap();
        var armed = EncryptingStorageTier.armLocalDisk(disk, mount, keyring()).unwrap();

        armed.tier().release();

        assertThat(LocalDiskTier.localDiskTier(mount, 1_000_000L).unwrap().open("node-b").isSuccess()).isTrue();
    }

    private static EncryptionKeyring keyring() {
        var key = new byte[32];

        new SecureRandom().nextBytes(key);

        var encryptor = BlockEncryptor.aesGcm(key, "k1").fold(c -> {
            fail("encryptor creation failed: " + c.message());
            return null;
        }, e -> e);

        return EncryptionKeyring.encryptionKeyring(Map.of("k1", encryptor), "k1").fold(c -> {
            fail("keyring creation failed: " + c.message());
            return null;
        }, k -> k);
    }
}
