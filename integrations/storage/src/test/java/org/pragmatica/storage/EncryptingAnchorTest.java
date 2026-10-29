package org.pragmatica.storage;

import java.security.SecureRandom;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.storage.AnchorTierTest.assertCorrupt;
import static org.pragmatica.storage.AnchorTierTest.await;
import static org.pragmatica.storage.AnchorTierTest.nameOf;
import static org.pragmatica.storage.AnchorTierTest.valueOf;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A3-0: [EncryptingStorageTier] forwards the anchor primitive, encrypting the value; the delegate sees only ciphertext.
@Timeout(30)
class EncryptingAnchorTest {
    private static final AnchorName NAME = nameOf("ahse-dir/content");

    @Test
    void theDelegateSeesOnlyCiphertext_andTheValueReadsBack() {
        var s3 = new AnchorTierTest.SortedS3();
        var tier = EncryptingStorageTier.wrap(remote(s3), keyring(Map.of("k1", randomKey()), "k1"));
        var value = "the-catalog-root-of-namespace-orders".getBytes();

        await(tier.putAnchor(NAME, 4, value));

        var stored = s3.objects.get("pfx/anchors/" + NAME.storageSegment() + "/" + AnchorFrame.key(4));

        assertThat(new String(stored)).doesNotContain("catalog-root");
        assertThat(await(tier.latestAnchor(NAME)).unwrap().value()).isEqualTo(value);
    }

    /// The key id travels in the frame, as for blocks: a version written under the old key reads after rotation.
    @Test
    void anAnchorWrittenBeforeKeyRotation_readsAfterIt() {
        var s3 = new AnchorTierTest.SortedS3();
        var oldKey = randomKey();

        await(EncryptingStorageTier.wrap(remote(s3), keyring(Map.of("k1", oldKey), "k1")).putAnchor(NAME, 1, valueOf(1)));

        var rotated = EncryptingStorageTier.wrap(remote(s3), keyring(Map.of("k1", oldKey, "k2", randomKey()), "k2"));

        assertThat(await(rotated.latestAnchor(NAME)).unwrap().value()).isEqualTo(valueOf(1));
        await(rotated.putAnchor(NAME, 2, valueOf(2)));
        assertThat(await(rotated.latestAnchor(NAME)).unwrap().value()).isEqualTo(valueOf(2));
    }

    /// The AAD binds name and version. A stored value copied onto a newer version (re-framed so the frame itself is
    /// valid) does not decrypt, and fails closed as corrupt.
    @Test
    void aValueCopiedOntoAnotherVersion_isCorrupt() {
        var s3 = new AnchorTierTest.SortedS3();
        var plain = remote(s3);
        var tier = EncryptingStorageTier.wrap(plain, keyring(Map.of("k1", randomKey()), "k1"));

        await(tier.putAnchor(NAME, 1, valueOf(1)));
        var ciphertextOfOne = await(plain.latestAnchor(NAME)).unwrap().value();
        await(plain.putAnchor(NAME, 2, ciphertextOfOne));

        assertCorrupt(tier.latestAnchor(NAME), TierLevel.REMOTE, 2);
    }

    /// Two writes of one version differ in bytes (fresh nonce) and decrypt to the same value; the later one wins and
    /// reads back as that same value.
    @Test
    void aLateDuplicateWriteOfOneVersion_readsBackTheSameValue() {
        var s3 = new AnchorTierTest.SortedS3();
        var plain = remote(s3);
        var tier = EncryptingStorageTier.wrap(plain, keyring(Map.of("k1", randomKey()), "k1"));

        await(tier.putAnchor(NAME, 3, valueOf(3)));
        var first = await(plain.latestAnchor(NAME)).unwrap().value();
        await(tier.putAnchor(NAME, 3, valueOf(3)));
        var second = await(plain.latestAnchor(NAME)).unwrap().value();

        assertThat(second).isNotEqualTo(first);
        assertThat(await(tier.latestAnchor(NAME)).unwrap().value()).isEqualTo(valueOf(3));
    }

    @Test
    void pruneIsForwarded() {
        var s3 = new AnchorTierTest.SortedS3();
        var tier = EncryptingStorageTier.wrap(remote(s3), keyring(Map.of("k1", randomKey()), "k1"));

        for (long version = 0; version <= 5; version++) {
            await(tier.putAnchor(NAME, version, valueOf(version)));
        }
        await(tier.pruneAnchors(NAME, 4, 100));

        assertThat(s3.versions("pfx/anchors/" + NAME.storageSegment() + "/")).containsExactlyInAnyOrder(4L, 5L);
    }

    private static RemoteTier remote(AnchorTierTest.SortedS3 s3) {
        return RemoteTier.durableRemoteTier(s3, "pfx", 8, timeSpan(10).seconds()).unwrap();
    }

    private static byte[] randomKey() {
        var key = new byte[32];

        new SecureRandom().nextBytes(key);

        return key;
    }

    private static EncryptionKeyring keyring(Map<String, byte[]> keys, String active) {
        var encryptors = new java.util.HashMap<String, BlockEncryptor>();

        keys.forEach((id, key) -> encryptors.put(id, BlockEncryptor.aesGcm(key, id).unwrap()));

        return EncryptionKeyring.encryptionKeyring(Map.copyOf(encryptors), active).unwrap();
    }
}
