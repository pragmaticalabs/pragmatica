package org.pragmatica.storage;

import java.util.Arrays;

/// Encryption parameters stored with each block for decryption.
///
/// @param algorithm cipher algorithm (e.g. "AES/GCM/NoPadding")
/// @param iv initialization vector used during encryption
/// @param keyId identifier of the encryption key used
public record EncryptionParams(String algorithm, byte[] iv, String keyId) {

    /// Sentinel for unencrypted blocks.
    public static final EncryptionParams NONE = new EncryptionParams("NONE", new byte[0], "");

    /// Defensive copy of mutable byte array.
    public EncryptionParams {
        iv = iv.clone();
    }

    public static EncryptionParams encryptionParams(String algorithm, byte[] iv, String keyId) {
        return new EncryptionParams(algorithm, iv, keyId);
    }

    /// Whether this block was encrypted.
    public boolean isEncrypted() {
        return this != NONE && !"NONE".equals(algorithm);
    }

    /// Defensive copy -- prevent external mutation of the IV.
    @Override
    public byte[] iv() {
        return iv.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EncryptionParams other
               && algorithm.equals(other.algorithm)
               && Arrays.equals(iv, other.iv)
               && keyId.equals(other.keyId);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * algorithm.hashCode() + Arrays.hashCode(iv)) + keyId.hashCode();
    }

    @Override
    public String toString() {
        return "EncryptionParams[algorithm=" + algorithm + ", keyId=" + keyId + "]";
    }
}
