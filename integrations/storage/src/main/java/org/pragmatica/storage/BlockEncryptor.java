package org.pragmatica.storage;

import java.util.Arrays;

import org.pragmatica.lang.Result;


/// Encrypts and decrypts block data.
public interface BlockEncryptor {
    /// Encrypt the given data, producing ciphertext and encryption parameters.
    Result<EncryptedData> encrypt(byte[] data);
    /// Decrypt the given ciphertext using the provided encryption parameters.
    Result<byte[]> decrypt(byte[] encryptedData, EncryptionParams params);

    /// Encrypt with additional authenticated data (AAD): `aad` is bound into the authentication
    /// tag but not itself encrypted, so a caller who stores `aad` alongside the ciphertext (e.g. a
    /// block-framing header) gets tamper-evidence on that header for free -- edit one byte of `aad`
    /// and [#decrypt(byte[], EncryptionParams, byte[])] fails authentication. Default delegates to
    /// [#encrypt(byte[])] and ignores `aad`, for encryptors (e.g. [NoOpEncryptor]) that don't
    /// support AAD; #253's [AesGcmBlockEncryptor] overrides both AAD-aware methods to make them the
    /// real crypto path.
    default Result<EncryptedData> encrypt(byte[] data, byte[] aad) {
        return encrypt(data);
    }

    /// AAD-aware counterpart to [#decrypt(byte[], EncryptionParams)] -- see
    /// [#encrypt(byte[], byte[])]. `aad` must be byte-for-byte identical to what was passed to
    /// `encrypt`, or authentication fails. Default delegates to [#decrypt(byte[], EncryptionParams)]
    /// and ignores `aad`.
    default Result<byte[]> decrypt(byte[] encryptedData, EncryptionParams params, byte[] aad) {
        return decrypt(encryptedData, params);
    }

    /// Encrypted data with its associated encryption parameters.
    record EncryptedData(byte[] ciphertext, EncryptionParams params) {
        /// Defensive copy of mutable byte array.
        public EncryptedData {
            ciphertext = ciphertext.clone();
        }

        public static EncryptedData encryptedData(byte[] ciphertext, EncryptionParams params) {
            return new EncryptedData(ciphertext, params);
        }

        /// Defensive copy -- prevent external mutation of the ciphertext.
        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof EncryptedData other
                   && Arrays.equals(ciphertext, other.ciphertext)
                   && params.equals(other.params);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(ciphertext) + params.hashCode();
        }
    }

    /// No-op encryptor for unencrypted storage.
    BlockEncryptor NONE = NoOpEncryptor.INSTANCE;

    /// Create an AES-256-GCM encryptor with the given key and key identifier.
    static Result<BlockEncryptor> aesGcm(byte[] key, String keyId) {
        return AesGcmBlockEncryptor.aesGcmBlockEncryptor(key, keyId);
    }
}
