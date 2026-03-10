package org.pragmatica.swim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.swim.AesGcmGossipEncryptor.aesGcmGossipEncryptor;

class AesGcmGossipEncryptorTest {
    private static final int KEY_SIZE = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private byte[] keyA;
    private byte[] keyB;
    private int keyIdA;
    private int keyIdB;

    @BeforeEach
    void setUp() {
        keyA = randomKey();
        keyB = randomKey();
        keyIdA = 1;
        keyIdB = 2;
    }

    private static byte[] randomKey() {
        var key = new byte[KEY_SIZE];
        RANDOM.nextBytes(key);
        return key;
    }

    @Nested
    class Creation {
        @Test
        void aesGcmGossipEncryptor_succeeds_withValidKey() {
            aesGcmGossipEncryptor(keyA, keyIdA)
                .onFailure(cause -> assertThat(cause).as("Expected success but got: " + cause.message()).isNull())
                .onSuccess(encryptor -> assertThat(encryptor).isNotNull());
        }

        @Test
        void aesGcmGossipEncryptor_fails_withInvalidKeySize() {
            var shortKey = new byte[16];
            RANDOM.nextBytes(shortKey);

            aesGcmGossipEncryptor(shortKey, keyIdA)
                .onSuccess(v -> assertThat(v).as("Expected failure for invalid key size").isNull())
                .onFailure(cause -> assertThat(cause).isInstanceOf(GossipEncryptionError.InvalidKeySize.class));
        }
    }

    @Nested
    class RoundTrip {
        @Test
        void encryptDecrypt_roundTrip_success() {
            var plaintext = "hello gossip".getBytes();

            aesGcmGossipEncryptor(keyA, keyIdA)
                .flatMap(enc -> enc.encrypt(plaintext).flatMap(enc::decrypt))
                .onFailure(cause -> assertThat(cause).as("Round trip should succeed: " + cause.message()).isNull())
                .onSuccess(decrypted -> assertThat(decrypted).isEqualTo(plaintext));
        }

        @Test
        void encryptDecrypt_emptyPayload_success() {
            var plaintext = new byte[0];

            aesGcmGossipEncryptor(keyA, keyIdA)
                .flatMap(enc -> enc.encrypt(plaintext).flatMap(enc::decrypt))
                .onFailure(cause -> assertThat(cause).as("Empty payload round trip should succeed: " + cause.message()).isNull())
                .onSuccess(decrypted -> assertThat(decrypted).isEqualTo(plaintext));
        }

        @Test
        void encryptDecrypt_largePayload_success() {
            var plaintext = new byte[65536];
            RANDOM.nextBytes(plaintext);

            aesGcmGossipEncryptor(keyA, keyIdA)
                .flatMap(enc -> enc.encrypt(plaintext).flatMap(enc::decrypt))
                .onFailure(cause -> assertThat(cause).as("Large payload round trip should succeed: " + cause.message()).isNull())
                .onSuccess(decrypted -> assertThat(decrypted).isEqualTo(plaintext));
        }
    }

    @Nested
    class DecryptionFailures {
        @Test
        void decrypt_wrongKey_fails() {
            var plaintext = "secret data".getBytes();

            aesGcmGossipEncryptor(keyA, keyIdA)
                .flatMap(encA -> encA.encrypt(plaintext)
                    .flatMap(ciphertext -> aesGcmGossipEncryptor(keyB, keyIdA)
                        .flatMap(encB -> encB.decrypt(ciphertext))))
                .onSuccess(v -> assertThat(v).as("Decryption with wrong key should fail").isNull());
        }

        @Test
        void decrypt_truncatedCiphertext_fails() {
            var plaintext = "some data".getBytes();

            aesGcmGossipEncryptor(keyA, keyIdA)
                .flatMap(enc -> enc.encrypt(plaintext)
                    .flatMap(ciphertext -> enc.decrypt(Arrays.copyOf(ciphertext, 10))))
                .onSuccess(v -> assertThat(v).as("Truncated ciphertext should fail").isNull());
        }
    }

    @Nested
    class DualKey {
        @Test
        void dualKey_decryptsWithPreviousKey() {
            var plaintext = "rotated message".getBytes();

            aesGcmGossipEncryptor(keyA, keyIdA)
                .flatMap(oldEnc -> oldEnc.encrypt(plaintext)
                    .flatMap(ciphertext -> aesGcmGossipEncryptor(keyB, keyIdB, keyA, keyIdA)
                        .flatMap(dualEnc -> dualEnc.decrypt(ciphertext))))
                .onFailure(cause -> assertThat(cause).as("Dual key decrypt should succeed: " + cause.message()).isNull())
                .onSuccess(decrypted -> assertThat(decrypted).isEqualTo(plaintext));
        }

        @Test
        void dualKey_rejectsUnknownKeyId() {
            var plaintext = "unknown key message".getBytes();
            var unknownKeyId = 999;

            aesGcmGossipEncryptor(keyA, unknownKeyId)
                .flatMap(enc -> enc.encrypt(plaintext)
                    .flatMap(ciphertext -> aesGcmGossipEncryptor(keyB, keyIdB, keyA, keyIdA)
                        .flatMap(dualEnc -> dualEnc.decrypt(ciphertext))))
                .onSuccess(v -> assertThat(v).as("Unknown key ID should fail").isNull())
                .onFailure(cause -> assertThat(cause).isInstanceOf(GossipEncryptionError.UnknownKeyId.class));
        }
    }

    @Nested
    class NoOp {
        @Test
        void noOpEncryptor_passthrough() {
            var data = "plaintext gossip".getBytes();
            var encryptor = GossipEncryptor.none();

            encryptor.encrypt(data)
                .onFailure(cause -> assertThat(cause).as("NoOp encrypt should succeed").isNull())
                .onSuccess(encrypted -> assertThat(encrypted).isEqualTo(data));

            encryptor.decrypt(data)
                .onFailure(cause -> assertThat(cause).as("NoOp decrypt should succeed").isNull())
                .onSuccess(decrypted -> assertThat(decrypted).isEqualTo(data));
        }
    }
}
