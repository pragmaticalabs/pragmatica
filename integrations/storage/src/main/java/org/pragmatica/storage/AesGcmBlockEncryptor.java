package org.pragmatica.storage;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;

import static org.pragmatica.storage.BlockEncryptor.EncryptedData.encryptedData;
import static org.pragmatica.storage.EncryptionParams.encryptionParams;

/// AES-256-GCM block encryptor with per-block random IV.
final class AesGcmBlockEncryptor implements BlockEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int REQUIRED_KEY_LENGTH = 32;
    private final byte[] key;
    private final String keyId;
    private final SecureRandom secureRandom;

    private AesGcmBlockEncryptor(byte[] key, String keyId) {
        this.key = key.clone();
        this.keyId = keyId;
        this.secureRandom = new SecureRandom();
    }

    static Result<BlockEncryptor> aesGcmBlockEncryptor(byte[] key, String keyId) {
        if (key.length != REQUIRED_KEY_LENGTH) {
            return new EncryptionError.InvalidKeyLength(key.length, REQUIRED_KEY_LENGTH).result();
        }
        return Result.success(new AesGcmBlockEncryptor(key, keyId));
    }

    @Override
    public Result<EncryptedData> encrypt(byte[] data) {
        return generateIv()
            .flatMap(iv -> performEncryption(data, iv));
    }

    @Override
    public Result<byte[]> decrypt(byte[] encryptedData, EncryptionParams params) {
        return doCipher(EncryptionError.DecryptionFailed::new, Cipher.DECRYPT_MODE, encryptedData, params.iv());
    }

    private Result<byte[]> generateIv() {
        var iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        return Result.success(iv);
    }

    private Result<EncryptedData> performEncryption(byte[] data, byte[] iv) {
        return doCipher(EncryptionError.EncryptionFailed::new, Cipher.ENCRYPT_MODE, data, iv)
            .map(ciphertext -> toEncryptedData(ciphertext, iv));
    }

    private EncryptedData toEncryptedData(byte[] ciphertext, byte[] iv) {
        return encryptedData(ciphertext, encryptionParams(ALGORITHM, iv, keyId));
    }

    /// Run the JCE cipher, capturing any [`GeneralSecurityException`] into a [`Result`] via the
    /// supplied error factory so the crypto-failure path is surfaced as a [`Cause`] rather than a
    /// checked exception propagated out of the encryptor.
    private Result<byte[]> doCipher(Fn1<? extends Cause, ? super Throwable> errorMapper, int mode, byte[] input, byte[] iv) {
        return Result.lift(errorMapper, () -> {
            var spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            var secretKey = new SecretKeySpec(key, "AES");
            var cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(mode, secretKey, spec);
            return cipher.doFinal(input);
        });
    }
}
