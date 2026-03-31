package org.pragmatica.storage;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.pragmatica.lang.Result;

import static org.pragmatica.storage.BlockEncryptor.EncryptedData.encryptedData;
import static org.pragmatica.storage.EncryptionParams.encryptionParams;

/// AES-256-GCM block encryptor with per-block random IV.
final class AesGcmBlockEncryptor implements BlockEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private final byte[] key;
    private final String keyId;
    private final SecureRandom secureRandom;

    private AesGcmBlockEncryptor(byte[] key, String keyId) {
        this.key = key.clone();
        this.keyId = keyId;
        this.secureRandom = new SecureRandom();
    }

    static BlockEncryptor aesGcmBlockEncryptor(byte[] key, String keyId) {
        return new AesGcmBlockEncryptor(key, keyId);
    }

    @Override
    public Result<EncryptedData> encrypt(byte[] data) {
        return generateIv()
            .flatMap(iv -> performEncryption(data, iv));
    }

    @Override
    public Result<byte[]> decrypt(byte[] encryptedData, EncryptionParams params) {
        return Result.lift(
            EncryptionError.DecryptionFailed::new,
            () -> doCipher(Cipher.DECRYPT_MODE, encryptedData, params.iv())
        );
    }

    private Result<byte[]> generateIv() {
        var iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        return Result.success(iv);
    }

    private Result<EncryptedData> performEncryption(byte[] data, byte[] iv) {
        return Result.lift(
            EncryptionError.EncryptionFailed::new,
            () -> doCipher(Cipher.ENCRYPT_MODE, data, iv)
        ).map(ciphertext -> toEncryptedData(ciphertext, iv));
    }

    private EncryptedData toEncryptedData(byte[] ciphertext, byte[] iv) {
        return encryptedData(ciphertext, encryptionParams(ALGORITHM, iv, keyId));
    }

    private byte[] doCipher(int mode, byte[] input, byte[] iv) throws Exception {
        var spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
        var secretKey = new SecretKeySpec(key, "AES");
        var cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(mode, secretKey, spec);
        return cipher.doFinal(input);
    }
}
