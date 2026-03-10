package org.pragmatica.swim;

import java.nio.ByteBuffer;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

/// AES-256-GCM gossip encryptor with dual-key support for rotation.
///
/// Wire format: [4-byte keyId (big-endian)][12-byte nonce][ciphertext + 16-byte GCM tag]
public final class AesGcmGossipEncryptor implements GossipEncryptor {
    private static final int KEY_SIZE = 32;
    private static final int NONCE_SIZE = 12;
    private static final int KEY_ID_SIZE = 4;
    private static final int GCM_TAG_BITS = 128;
    private static final int HEADER_SIZE = KEY_ID_SIZE + NONCE_SIZE;
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    private final SecretKeySpec currentKey;
    private final int currentKeyId;
    private final Option<SecretKeySpec> previousKey;
    private final Option<Integer> previousKeyId;
    private final SecureRandom secureRandom;

    private AesGcmGossipEncryptor(SecretKeySpec currentKey, int currentKeyId,
                                   Option<SecretKeySpec> previousKey, Option<Integer> previousKeyId) {
        this.currentKey = currentKey;
        this.currentKeyId = currentKeyId;
        this.previousKey = previousKey;
        this.previousKeyId = previousKeyId;
        this.secureRandom = new SecureRandom();
    }

    /// Factory creating an encryptor with a single key.
    public static Result<GossipEncryptor> aesGcmGossipEncryptor(byte[] currentKey, int currentKeyId) {
        return validateKey(currentKey)
            .map(key -> new AesGcmGossipEncryptor(key, currentKeyId, Option.none(), Option.none()));
    }

    /// Factory creating an encryptor with current and previous keys for rotation.
    public static Result<GossipEncryptor> aesGcmGossipEncryptor(byte[] currentKey, int currentKeyId,
                                                                 byte[] previousKey, int previousKeyId) {
        return Result.all(validateKey(currentKey), validateKey(previousKey))
                     .map((cur, prev) -> new AesGcmGossipEncryptor(cur, currentKeyId,
                                                                    Option.some(prev),
                                                                    Option.some(previousKeyId)));
    }

    @Override
    public Result<byte[]> encrypt(byte[] plaintext) {
        return Result.lift(GossipEncryptionError.EncryptionFailed::new, () -> doEncrypt(plaintext));
    }

    @Override
    public Result<byte[]> decrypt(byte[] ciphertext) {
        return parseHeader(ciphertext)
            .flatMap(header -> resolveKey(header.keyId()).flatMap(key -> decryptPayload(key, header)));
    }

    private byte[] doEncrypt(byte[] plaintext) throws Exception {
        var nonce = new byte[NONCE_SIZE];
        secureRandom.nextBytes(nonce);

        var cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, currentKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));

        var encrypted = cipher.doFinal(plaintext);

        return ByteBuffer.allocate(HEADER_SIZE + encrypted.length)
                         .putInt(currentKeyId)
                         .put(nonce)
                         .put(encrypted)
                         .array();
    }

    private static Result<ParsedHeader> parseHeader(byte[] ciphertext) {
        return Result.lift(GossipEncryptionError.DecryptionFailed::new, () -> doParse(ciphertext));
    }

    private static ParsedHeader doParse(byte[] ciphertext) {
        var buffer = ByteBuffer.wrap(ciphertext);
        var keyId = buffer.getInt();
        var nonce = new byte[NONCE_SIZE];
        buffer.get(nonce);
        var encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);

        return new ParsedHeader(keyId, nonce, encrypted);
    }

    private Result<byte[]> decryptPayload(SecretKeySpec key, ParsedHeader header) {
        return Result.lift(GossipEncryptionError.DecryptionFailed::new, () -> doDecryptPayload(key, header));
    }

    private static byte[] doDecryptPayload(SecretKeySpec key, ParsedHeader header) throws Exception {
        var cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, header.nonce()));

        return cipher.doFinal(header.encrypted());
    }

    private Result<SecretKeySpec> resolveKey(int keyId) {
        if (keyId == currentKeyId) {
            return Result.success(currentKey);
        }

        return previousKey.filter(_ -> previousKeyId.map(id -> id == keyId).or(false))
                          .toResult(new GossipEncryptionError.UnknownKeyId(keyId));
    }

    private static Result<SecretKeySpec> validateKey(byte[] key) {
        if (key.length != KEY_SIZE) {
            return new GossipEncryptionError.InvalidKeySize(key.length).result();
        }

        return Result.success(new SecretKeySpec(key, "AES"));
    }

    private record ParsedHeader(int keyId, byte[] nonce, byte[] encrypted) {}
}
