package org.pragmatica.storage;

import java.security.SecureRandom;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #253: [EncryptionKeyring]'s own construction-time guarantee -- an active key id absent from
/// the key map must never produce a keyring at all, since [EncryptingStorageTier#active()] relies
/// on that guarantee to look up the active encryptor unconditionally.
class EncryptionKeyringTest {

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

    @Test
    void encryptionKeyring_succeeds_whenActiveIdPresentInKeys() {
        var encryptor = encryptorOf("key-1");

        EncryptionKeyring.encryptionKeyring(Map.of("key-1", encryptor), "key-1")
                          .onFailure(c -> fail("construction should succeed: " + c.message()))
                          .onSuccess(ring -> assertThat(ring.active()).isSameAs(encryptor));
    }

    @Test
    void encryptionKeyring_fails_whenActiveIdAbsentFromKeys() {
        var encryptor = encryptorOf("key-1");

        EncryptionKeyring.encryptionKeyring(Map.of("key-1", encryptor), "does-not-exist")
                          .onSuccess(_ -> fail("construction must refuse an active id absent from the key map"))
                          .onFailure(cause -> assertThat(cause).isInstanceOf(EncryptionError.UnknownKeyId.class));
    }

    @Test
    void byKeyId_found_returnsTheEncryptor() {
        var encryptor = encryptorOf("key-1");
        var ring = EncryptionKeyring.encryptionKeyring(Map.of("key-1", encryptor), "key-1")
                                     .fold(c -> { fail("keyring creation failed: " + c.message()); return null; },
                                           k -> k);

        ring.byKeyId("key-1")
            .onFailure(c -> fail("lookup should succeed: " + c.message()))
            .onSuccess(found -> assertThat(found).isSameAs(encryptor));
    }

    @Test
    void byKeyId_notFound_fails_withUnknownKeyId_namingTheId() {
        var ring = EncryptionKeyring.encryptionKeyring(Map.of("key-1", encryptorOf("key-1")), "key-1")
                                     .fold(c -> { fail("keyring creation failed: " + c.message()); return null; },
                                           k -> k);

        ring.byKeyId("retired-key")
            .onSuccess(_ -> fail("lookup of an absent key id should fail"))
            .onFailure(cause -> {
                assertThat(cause).isInstanceOf(EncryptionError.UnknownKeyId.class);
                assertThat(cause.message()).contains("retired-key");
            });
    }

    @Test
    void byKeyId_retiredButPresentKey_stillResolves_forDecryptingOldBlocks() {
        var activeEncryptor = encryptorOf("key-2");
        var retiredEncryptor = encryptorOf("key-1");
        var ring = EncryptionKeyring.encryptionKeyring(Map.of("key-1", retiredEncryptor, "key-2", activeEncryptor), "key-2")
                                     .fold(c -> { fail("keyring creation failed: " + c.message()); return null; },
                                           k -> k);

        ring.byKeyId("key-1")
            .onFailure(c -> fail("a retired but still-present key must remain resolvable: " + c.message()))
            .onSuccess(found -> assertThat(found).isSameAs(retiredEncryptor));
    }
}
