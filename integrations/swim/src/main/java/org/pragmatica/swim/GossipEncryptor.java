package org.pragmatica.swim;

import org.pragmatica.lang.Result;

/// Encryption layer for SWIM gossip messages.
/// Supports transparent encryption/decryption with key rotation.
public interface GossipEncryptor {
    /// Encrypt plaintext bytes for transmission.
    Result<byte[]> encrypt(byte[] plaintext);

    /// Decrypt received ciphertext bytes.
    Result<byte[]> decrypt(byte[] ciphertext);

    /// No-op encryptor that passes data through unchanged.
    static GossipEncryptor none() {
        return NoOpGossipEncryptor.INSTANCE;
    }
}
