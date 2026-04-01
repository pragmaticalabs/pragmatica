package org.pragmatica.swim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.swim.AesGcmGossipEncryptor.aesGcmGossipEncryptor;
import static org.pragmatica.swim.RotatingGossipEncryptor.rotatingGossipEncryptor;

class RotatingGossipEncryptorTest {
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
    class Delegation {
        @Test
        void encrypt_delegates_toInitialEncryptor() {
            var plaintext = "hello".getBytes();

            aesGcmGossipEncryptor(keyA, keyIdA)
                .map(RotatingGossipEncryptor::rotatingGossipEncryptor)
                .flatMap(enc -> enc.encrypt(plaintext).flatMap(enc::decrypt))
                .onFailure(cause -> assertThat(cause).as("Delegation round trip should succeed: " + cause.message()).isNull())
                .onSuccess(decrypted -> assertThat(decrypted).isEqualTo(plaintext));
        }

        @Test
        void noOp_passthrough_byDefault() {
            var data = "passthrough".getBytes();
            var enc = rotatingGossipEncryptor(GossipEncryptor.none());

            enc.encrypt(data)
               .onFailure(cause -> assertThat(cause).as("NoOp encrypt should succeed").isNull())
               .onSuccess(encrypted -> assertThat(encrypted).isEqualTo(data));
        }
    }

    @Nested
    class Rotation {
        @Test
        void rotate_newEncryptor_usedForEncrypt() {
            var plaintext = "rotated".getBytes();
            var rotating = rotatingGossipEncryptor(GossipEncryptor.none());

            aesGcmGossipEncryptor(keyA, keyIdA)
                .onSuccess(rotating::rotate);

            // After rotation, encrypt uses new encryptor — decrypt with same key succeeds
            aesGcmGossipEncryptor(keyA, keyIdA)
                .flatMap(verifier -> rotating.encrypt(plaintext).flatMap(verifier::decrypt))
                .onFailure(cause -> assertThat(cause).as("Post-rotation round trip should succeed: " + cause.message()).isNull())
                .onSuccess(decrypted -> assertThat(decrypted).isEqualTo(plaintext));
        }

        @Test
        void rotate_dualKey_decryptsBothOldAndNew() {
            var plaintext = "overlap".getBytes();

            // Encrypt with keyA (old encryptor)
            var cipherA = aesGcmGossipEncryptor(keyA, keyIdA)
                              .flatMap(enc -> enc.encrypt(plaintext))
                              .or(new byte[0]);

            // Create rotating encryptor, then rotate to dual-key (keyB current, keyA previous)
            var rotating = rotatingGossipEncryptor(GossipEncryptor.none());
            aesGcmGossipEncryptor(keyB, keyIdB, keyA, keyIdA)
                .onSuccess(rotating::rotate);

            // Should decrypt message encrypted with old key
            rotating.decrypt(cipherA)
                    .onFailure(cause -> assertThat(cause).as("Dual-key decrypt of old message should succeed: " + cause.message()).isNull())
                    .onSuccess(decrypted -> assertThat(decrypted).isEqualTo(plaintext));

            // Should also encrypt/decrypt with new key
            rotating.encrypt(plaintext)
                    .flatMap(rotating::decrypt)
                    .onFailure(cause -> assertThat(cause).as("New key round trip should succeed: " + cause.message()).isNull())
                    .onSuccess(decrypted -> assertThat(decrypted).isEqualTo(plaintext));
        }
    }
}
