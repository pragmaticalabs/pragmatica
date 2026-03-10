package org.pragmatica.swim;

import org.pragmatica.lang.Result;

/// No-op encryptor that passes data through unchanged.
/// Used when gossip encryption is disabled (e.g., LOCAL environment).
enum NoOpGossipEncryptor implements GossipEncryptor {
    INSTANCE;

    @Override
    public Result<byte[]> encrypt(byte[] plaintext) {
        return Result.success(plaintext);
    }

    @Override
    public Result<byte[]> decrypt(byte[] ciphertext) {
        return Result.success(ciphertext);
    }
}
