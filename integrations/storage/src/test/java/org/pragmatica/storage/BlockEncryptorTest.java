package org.pragmatica.storage;

import java.security.SecureRandom;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class BlockEncryptorTest {

    private static final String KEY_ID = "test-key-1";
    private static final byte[] TEST_DATA = "Hello, AES-256-GCM encryption!".getBytes();

    private byte[] key;
    private BlockEncryptor encryptor;

    @BeforeEach
    void setUp() {
        key = new byte[32];
        new SecureRandom().nextBytes(key);
        encryptor = BlockEncryptor.aesGcm(key, KEY_ID).unwrap();
    }

    @Nested
    class AesGcmEncryption {

        @Test
        void encrypt_decrypt_roundTrip() {
            encryptor.encrypt(TEST_DATA)
                     .flatMap(encrypted -> encryptor.decrypt(encrypted.ciphertext(), encrypted.params()))
                     .onFailure(cause -> fail("Round-trip failed: " + cause.message()))
                     .onSuccess(decrypted -> assertThat(decrypted).isEqualTo(TEST_DATA));
        }

        @Test
        void encrypt_producesUniqueIV_eachCall() {
            var result1 = encryptor.encrypt(TEST_DATA);
            var result2 = encryptor.encrypt(TEST_DATA);

            result1.onFailure(cause -> fail("First encryption failed: " + cause.message()))
                   .onSuccess(enc1 ->
                       result2.onFailure(cause -> fail("Second encryption failed: " + cause.message()))
                              .onSuccess(enc2 -> assertThat(enc1.params().iv()).isNotEqualTo(enc2.params().iv()))
                   );
        }

        @Test
        void encrypt_differentKey_cannotDecrypt() {
            var otherKey = new byte[32];
            new SecureRandom().nextBytes(otherKey);
            var otherEncryptor = BlockEncryptor.aesGcm(otherKey, "other-key").unwrap();

            encryptor.encrypt(TEST_DATA)
                     .flatMap(encrypted -> otherEncryptor.decrypt(encrypted.ciphertext(), encrypted.params()))
                     .onSuccess(_ -> fail("Decryption with wrong key should fail"));
        }

        @Test
        void encrypt_tamperedCiphertext_failsDecrypt() {
            encryptor.encrypt(TEST_DATA)
                     .onFailure(cause -> fail("Encryption failed: " + cause.message()))
                     .onSuccess(this::verifyTamperedCiphertextFails);
        }

        private void verifyTamperedCiphertextFails(BlockEncryptor.EncryptedData encrypted) {
            var tampered = encrypted.ciphertext();
            tampered[0] ^= 0xFF;
            encryptor.decrypt(tampered, encrypted.params())
                     .onSuccess(_ -> fail("Tampered ciphertext should fail decryption"));
        }

        @Test
        void encrypt_emptyData_succeeds() {
            encryptor.encrypt(new byte[0])
                     .flatMap(encrypted -> encryptor.decrypt(encrypted.ciphertext(), encrypted.params()))
                     .onFailure(cause -> fail("Empty data round-trip failed: " + cause.message()))
                     .onSuccess(decrypted -> assertThat(decrypted).isEmpty());
        }

        @Test
        void encrypt_largeData_succeeds() {
            var largeData = new byte[1024 * 1024];
            Arrays.fill(largeData, (byte) 0xAB);

            encryptor.encrypt(largeData)
                     .flatMap(encrypted -> encryptor.decrypt(encrypted.ciphertext(), encrypted.params()))
                     .onFailure(cause -> fail("Large data round-trip failed: " + cause.message()))
                     .onSuccess(decrypted -> assertThat(decrypted).isEqualTo(largeData));
        }

        @Test
        void encrypt_preservesKeyId() {
            encryptor.encrypt(TEST_DATA)
                     .onFailure(cause -> fail("Encryption failed: " + cause.message()))
                     .onSuccess(encrypted -> assertThat(encrypted.params().keyId()).isEqualTo(KEY_ID));
        }

        @Test
        void encrypt_preservesAlgorithm() {
            encryptor.encrypt(TEST_DATA)
                     .onFailure(cause -> fail("Encryption failed: " + cause.message()))
                     .onSuccess(encrypted -> assertThat(encrypted.params().algorithm()).isEqualTo("AES/GCM/NoPadding"));
        }

        @Test
        void aesGcm_invalidKeyLength_returnsFailure() {
            var shortKey = new byte[16];
            new SecureRandom().nextBytes(shortKey);

            BlockEncryptor.aesGcm(shortKey, KEY_ID)
                          .onSuccess(_ -> fail("Should fail with invalid key length"))
                          .onFailure(cause -> assertThat(cause).isInstanceOf(EncryptionError.InvalidKeyLength.class));
        }

        @Test
        void encrypt_ciphertextDiffersFromPlaintext() {
            encryptor.encrypt(TEST_DATA)
                     .onFailure(cause -> fail("Encryption failed: " + cause.message()))
                     .onSuccess(encrypted -> assertThat(encrypted.ciphertext()).isNotEqualTo(TEST_DATA));
        }
    }

    @Nested
    class NoOpEncryption {

        @Test
        void noOpEncryptor_passesThrough() {
            var noop = BlockEncryptor.NONE;

            noop.encrypt(TEST_DATA)
                .onFailure(cause -> fail("NoOp encryption failed: " + cause.message()))
                .onSuccess(NoOpEncryption::verifyNoOpEncryption);
        }

        private static void verifyNoOpEncryption(BlockEncryptor.EncryptedData encrypted) {
            assertThat(encrypted.ciphertext()).isEqualTo(TEST_DATA);
            assertThat(encrypted.params().isEncrypted()).isFalse();
        }

        @Test
        void noOpEncryptor_decrypt_passesThrough() {
            var noop = BlockEncryptor.NONE;

            noop.decrypt(TEST_DATA, EncryptionParams.NONE)
                .onFailure(cause -> fail("NoOp decryption failed: " + cause.message()))
                .onSuccess(decrypted -> assertThat(decrypted).isEqualTo(TEST_DATA));
        }
    }

    @Nested
    class EncryptionParamsTest {

        @Test
        void none_isNotEncrypted() {
            assertThat(EncryptionParams.NONE.isEncrypted()).isFalse();
        }

        @Test
        void withAlgorithm_isEncrypted() {
            var params = EncryptionParams.encryptionParams("AES/GCM/NoPadding", new byte[12], "key-1");
            assertThat(params.isEncrypted()).isTrue();
        }

        @Test
        void defensiveCopy_ivNotShared() {
            var iv = new byte[]{1, 2, 3};
            var params = EncryptionParams.encryptionParams("AES/GCM/NoPadding", iv, "key-1");
            iv[0] = 99;
            assertThat(params.iv()[0]).isEqualTo((byte) 1);
        }
    }
}
